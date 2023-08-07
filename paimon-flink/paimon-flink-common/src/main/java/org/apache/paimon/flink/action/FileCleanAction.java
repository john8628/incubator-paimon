/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.action;

import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.table.AbstractFileStoreTable;
import org.apache.paimon.table.FileStoreTable;

import org.apache.commons.compress.utils.Lists;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Delete from table action for Flink. */
public class FileCleanAction extends TableActionBase {

    private static final Logger LOG = LoggerFactory.getLogger(FileCleanAction.class);
    private static final int MAX_DRIVER_LISTING_DEPTH = 3;
    private static final int MAX_DRIVER_LISTING_DIRECT_SUB_DIRS = 10;

    private Path basePath;
    private FileIO fileIO;

    public FileCleanAction(
            String warehouse,
            String databaseName,
            String tableName,
            Map<String, String> catalogConfig) {
        super(warehouse, databaseName, tableName, catalogConfig);

        if (!(table instanceof FileStoreTable)) {
            throw new UnsupportedOperationException(
                    String.format(
                            "Only FileStoreTable supports drop-partition action. The table type is '%s'.",
                            table.getClass().getName()));
        }
        AbstractFileStoreTable fileStoreTable = (AbstractFileStoreTable) table;
        basePath = fileStoreTable.location();
        fileIO = fileStoreTable.fileIO();
    }

    @Override
    public void run() throws Exception {
        LOG.debug(
                "Scan all files and filter out that still used by at least one snapshot, then delete those not used by any snapshot..");
        // 1: get all files  of the table path
        List<String> allFileList = buildAllFileList(basePath.getPath());
        // 2: get all the used files of the table;exclude the manifest , schema  and snapshot files;
        List<String> validFileNameList = buildValidFileNameList();
        // 3: get the diff of  the files
        allFileList.remove(validFileNameList);

        // 4:delete the file
        allFileList.stream()
                .flatMap(
                        s -> {
                            try {
                                fileIO.delete(new Path(s), true);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            return null;
                        });
    }

    /**
     * query the valid files of the table
     *
     * @return
     */
    private List<String> buildValidFileNameList() {
        org.apache.flink.table.api.Table queriedTable =
                batchTEnv.sqlQuery(
                        String.format(
                                "SELECT * FROM %s ", identifier.getEscapedFullName() + "$files"));
        CloseableIterator<Row> rowIterator = queriedTable.execute().collect();
        List<String> validFileNames = new ArrayList<String>();
        rowIterator.forEachRemaining(
                row -> {
                    validFileNames.add((String) row.getField(2));
                });
        return validFileNames;
    }

    private List<String> buildAllFileList(String basePath) {

        List<String> subDirs = Lists.newArrayList();
        List<String> matchingFiles = Lists.newArrayList();

        listDirRecursively(
                basePath,
                fileIO,
                MAX_DRIVER_LISTING_DEPTH,
                MAX_DRIVER_LISTING_DIRECT_SUB_DIRS,
                subDirs,
                matchingFiles);
        return matchingFiles;
    }

    private static void listDirRecursively(
            String dir,
            FileIO fileIO,
            int maxDepth,
            int maxDirectSubDirs,
            List<String> remainingSubDirs,
            List<String> matchingFiles) {

        // stop listing whenever we reach the max depth
        if (maxDepth <= 0) {
            remainingSubDirs.add(dir);
            return;
        }

        try {
            Path path = new Path(dir);

            List<String> subDirs = Lists.newArrayList();

            for (org.apache.paimon.fs.FileStatus file : fileIO.listStatus(path)) {
                if (file.isDir()) {
                    subDirs.add(file.getPath().toString());
                } else if (!file.isDir()) {
                    matchingFiles.add(file.getPath().toString());
                }
            }

            // stop listing if the number of direct sub dirs is bigger than maxDirectSubDirs
            if (subDirs.size() > maxDirectSubDirs) {
                remainingSubDirs.addAll(subDirs);
                return;
            }

            for (String subDir : subDirs) {
                listDirRecursively(
                        subDir,
                        fileIO,
                        maxDepth - 1,
                        maxDirectSubDirs,
                        remainingSubDirs,
                        matchingFiles);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
