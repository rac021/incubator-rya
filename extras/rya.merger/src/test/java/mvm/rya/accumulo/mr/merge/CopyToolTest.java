package mvm.rya.accumulo.mr.merge;

/*
 * #%L
 * mvm.rya.accumulo.mr.merge
 * %%
 * Copyright (C) 2014 Rya
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import static mvm.rya.accumulo.mr.merge.util.TestUtils.LAST_MONTH;
import static mvm.rya.accumulo.mr.merge.util.TestUtils.TODAY;
import static mvm.rya.accumulo.mr.merge.util.TestUtils.YESTERDAY;
import static mvm.rya.accumulo.mr.merge.util.TestUtils.createRyaStatement;
import static mvm.rya.accumulo.mr.merge.util.ToolConfigUtils.makeArgument;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.admin.SecurityOperations;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.file.rfile.bcfile.Compression.Algorithm;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.hadoop.conf.Configuration;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import info.aduna.iteration.CloseableIteration;
import mvm.rya.accumulo.AccumuloRdfConfiguration;
import mvm.rya.accumulo.AccumuloRyaDAO;
import mvm.rya.accumulo.mr.MRUtils;
import mvm.rya.accumulo.mr.merge.common.InstanceType;
import mvm.rya.accumulo.mr.merge.driver.AccumuloDualInstanceDriver;
import mvm.rya.accumulo.mr.merge.util.AccumuloRyaUtils;
import mvm.rya.accumulo.mr.merge.util.TestUtils;
import mvm.rya.accumulo.mr.merge.util.TimeUtils;
import mvm.rya.api.RdfCloudTripleStoreConfiguration;
import mvm.rya.api.RdfCloudTripleStoreConstants;
import mvm.rya.api.domain.RyaStatement;
import mvm.rya.api.persist.RyaDAOException;
import mvm.rya.indexing.accumulo.ConfigUtils;

/**
 * Tests for {@link CopyTool}.
 */
public class CopyToolTest {
    private static final Logger log = Logger.getLogger(CopyToolTest.class);

    private static final boolean IS_MOCK = true;
    private static final boolean USE_TIME_SYNC = false;
    private static final boolean USE_COPY_FILE_OUTPUT = false;
    private static final boolean IS_START_TIME_DIALOG_ENABLED = false;

    private static final String CHILD_SUFFIX = MergeTool.CHILD_SUFFIX;

    private static final String PARENT_PASSWORD = AccumuloDualInstanceDriver.PARENT_PASSWORD;
    private static final String PARENT_INSTANCE = AccumuloDualInstanceDriver.PARENT_INSTANCE;
    private static final String PARENT_TABLE_PREFIX = AccumuloDualInstanceDriver.PARENT_TABLE_PREFIX;
    private static final String PARENT_AUTH = AccumuloDualInstanceDriver.PARENT_AUTH;
    private static final ColumnVisibility PARENT_COLUMN_VISIBILITY = new ColumnVisibility(PARENT_AUTH);
    private static final String PARENT_TOMCAT_URL = "http://rya-example-box:8080";

    private static final String CHILD_PASSWORD = AccumuloDualInstanceDriver.CHILD_PASSWORD;
    private static final String CHILD_INSTANCE = AccumuloDualInstanceDriver.CHILD_INSTANCE;
    private static final String CHILD_TABLE_PREFIX = AccumuloDualInstanceDriver.CHILD_TABLE_PREFIX;
    private static final String CHILD_TOMCAT_URL = "http://localhost:8080";

    private static AccumuloRyaDAO parentDao;
    private static AccumuloRyaDAO childDao;

    private static AccumuloRdfConfiguration parentConfig;
    private static AccumuloRdfConfiguration childConfig;

    private static AccumuloDualInstanceDriver accumuloDualInstanceDriver;
    private static CopyTool copyTool = null;
    private boolean isImporting = false;

    @BeforeClass
    public static void setUp() throws Exception {
        accumuloDualInstanceDriver = new AccumuloDualInstanceDriver(IS_MOCK, true, true, false, false);
        accumuloDualInstanceDriver.setUpInstances();
    }

    @Before
    public void setUpPerTest() throws Exception {
        accumuloDualInstanceDriver.setUpTables();

        accumuloDualInstanceDriver.setUpDaos();

        accumuloDualInstanceDriver.setUpConfigs();

        parentConfig = accumuloDualInstanceDriver.getParentConfig();
        childConfig = accumuloDualInstanceDriver.getChildConfig();
        parentDao = accumuloDualInstanceDriver.getParentDao();
        childDao = accumuloDualInstanceDriver.getChildDao();
    }

    @After
    public void tearDownPerTest() throws Exception {
        log.info("tearDownPerTest(): tearing down now.");
        accumuloDualInstanceDriver.tearDownTables();
        accumuloDualInstanceDriver.tearDownDaos();
        if (copyTool != null) {
            copyTool.shutdown();
        }
    }

    @AfterClass
    public static void tearDownPerClass() throws Exception {
        log.info("tearDownPerClass(): tearing down now.");
        accumuloDualInstanceDriver.tearDown();
    }

    private void assertStatementInChild(String description, int verifyResultCount, RyaStatement matchStatement) throws RyaDAOException {
        TestUtils.assertStatementInInstance(description, verifyResultCount, matchStatement, childDao, childConfig);
    }

    private void copyToolRun(Date startDate) throws AccumuloException, AccumuloSecurityException {
        copyTool = new CopyTool();
        copyTool.setupAndRun(new String[] {
                makeArgument(MRUtils.AC_MOCK_PROP, Boolean.toString(IS_MOCK)),
                makeArgument(MRUtils.AC_INSTANCE_PROP, PARENT_INSTANCE),
                makeArgument(MRUtils.AC_USERNAME_PROP, accumuloDualInstanceDriver.getParentUser()),
                makeArgument(MRUtils.AC_PWD_PROP, PARENT_PASSWORD),
                makeArgument(MRUtils.TABLE_PREFIX_PROPERTY, PARENT_TABLE_PREFIX),
                makeArgument(MRUtils.AC_AUTH_PROP, accumuloDualInstanceDriver.getParentAuths().toString()),
                makeArgument(MRUtils.AC_ZK_PROP, accumuloDualInstanceDriver.getParentZooKeepers()),
                makeArgument(CopyTool.PARENT_TOMCAT_URL_PROP, PARENT_TOMCAT_URL),
                makeArgument(MRUtils.AC_MOCK_PROP + CHILD_SUFFIX, Boolean.toString(IS_MOCK)),
                makeArgument(MRUtils.AC_INSTANCE_PROP + CHILD_SUFFIX, CHILD_INSTANCE),
                makeArgument(MRUtils.AC_USERNAME_PROP + CHILD_SUFFIX, accumuloDualInstanceDriver.getChildUser()),
                makeArgument(MRUtils.AC_PWD_PROP + CHILD_SUFFIX, CHILD_PASSWORD),
                makeArgument(MRUtils.TABLE_PREFIX_PROPERTY + CHILD_SUFFIX, CHILD_TABLE_PREFIX),
                makeArgument(MRUtils.AC_AUTH_PROP + CHILD_SUFFIX, accumuloDualInstanceDriver.getChildAuths() != null ? accumuloDualInstanceDriver.getChildAuths().toString() : null),
                makeArgument(MRUtils.AC_ZK_PROP + CHILD_SUFFIX, accumuloDualInstanceDriver.getChildZooKeepers() != null ? accumuloDualInstanceDriver.getChildZooKeepers() : "localhost"),
                makeArgument(CopyTool.CHILD_TOMCAT_URL_PROP, CHILD_TOMCAT_URL),
                makeArgument(CopyTool.CREATE_CHILD_INSTANCE_TYPE_PROP, (IS_MOCK ? InstanceType.MOCK : InstanceType.MINI).toString()),
                makeArgument(CopyTool.NTP_SERVER_HOST_PROP, TimeUtils.DEFAULT_TIME_SERVER_HOST),
                makeArgument(CopyTool.USE_NTP_SERVER_PROP, Boolean.toString(USE_TIME_SYNC)),
                makeArgument(CopyTool.USE_COPY_FILE_OUTPUT, Boolean.toString(USE_COPY_FILE_OUTPUT)),
                makeArgument(CopyTool.COPY_FILE_OUTPUT_PATH, "/test/copy_tool_file_output/"),
                makeArgument(CopyTool.COPY_FILE_OUTPUT_COMPRESSION_TYPE, Algorithm.GZ.getName()),
                makeArgument(CopyTool.USE_COPY_FILE_OUTPUT_DIRECTORY_CLEAR, Boolean.toString(true)),
                makeArgument(CopyTool.COPY_FILE_IMPORT_DIRECTORY, "resources/test/copy_tool_file_output/"),
                makeArgument(CopyTool.USE_COPY_FILE_IMPORT, Boolean.toString(isImporting)),
                makeArgument(MergeTool.START_TIME_PROP, MergeTool.getStartTimeString(startDate, IS_START_TIME_DIALOG_ENABLED))
        });

        Configuration toolConfig = copyTool.getConf();
        String zooKeepers = toolConfig.get(MRUtils.AC_ZK_PROP + CHILD_SUFFIX);
        MergeTool.setDuplicateKeysForProperty(childConfig, MRUtils.AC_ZK_PROP, zooKeepers);

        log.info("Finished running tool.");
    }

    @Test
    public void testCopyTool() throws Exception {
        RyaStatement ryaStatementOutOfTimeRange = createRyaStatement("coach", "called", "timeout", LAST_MONTH);

        RyaStatement ryaStatementShouldCopy1 = createRyaStatement("bob", "catches", "ball", YESTERDAY);
        RyaStatement ryaStatementShouldCopy2 = createRyaStatement("bill", "talks to", "john", YESTERDAY);
        RyaStatement ryaStatementShouldCopy3 = createRyaStatement("susan", "eats", "burgers", TODAY);
        RyaStatement ryaStatementShouldCopy4 = createRyaStatement("ronnie", "plays", "guitar", TODAY);

        RyaStatement ryaStatementDoesNotExist1 = createRyaStatement("nobody", "was", "here", LAST_MONTH);
        RyaStatement ryaStatementDoesNotExist2 = createRyaStatement("statement", "not", "found", YESTERDAY);
        RyaStatement ryaStatementDoesNotExist3 = createRyaStatement("key", "does not", "exist", TODAY);

        // This statement was modified by the child to change the column visibility.
        // The parent should combine the child's visibility with its visibility.
        RyaStatement ryaStatementVisibilityDifferent = createRyaStatement("I", "see", "you", YESTERDAY);
        ryaStatementVisibilityDifferent.setColumnVisibility(PARENT_COLUMN_VISIBILITY.getExpression());

        // Setup initial parent instance with 7 rows
        // This is the state of the parent data (as it is today) before merging occurs which will use the specified start time of yesterday.
        parentDao.add(ryaStatementOutOfTimeRange);      // Process should NOT copy statement
        parentDao.add(ryaStatementShouldCopy1);         // Process should copy statement
        parentDao.add(ryaStatementShouldCopy2);         // Process should copy statement
        parentDao.add(ryaStatementShouldCopy3);         // Process should copy statement
        parentDao.add(ryaStatementShouldCopy4);         // Process should copy statement
        parentDao.add(ryaStatementVisibilityDifferent); // Process should copy and update statement


        AccumuloRyaUtils.printTable(PARENT_TABLE_PREFIX + RdfCloudTripleStoreConstants.TBL_SPO_SUFFIX, parentConfig);
        //AccumuloRyaUtils.printTable(CHILD_TABLE_PREFIX + RdfCloudTripleStoreConstants.TBL_SPO_SUFFIX, childConfig);

        log.info("Starting copy tool. Copying all data after the specified start time: " + YESTERDAY);

        isImporting = false;
        copyToolRun(YESTERDAY);


        // Copy Tool made child instance so hook the tables and dao into the driver.
        String childUser = accumuloDualInstanceDriver.getChildUser();
        Connector childConnector = ConfigUtils.getConnector(childConfig);
        accumuloDualInstanceDriver.getChildAccumuloInstanceDriver().setConnector(childConnector);

        accumuloDualInstanceDriver.getChildAccumuloInstanceDriver().setUpTables();

        accumuloDualInstanceDriver.getChildAccumuloInstanceDriver().setUpDao();
        childDao = accumuloDualInstanceDriver.getChildDao();


        // Update child config to include changes made from copy process
        SecurityOperations childSecOps = accumuloDualInstanceDriver.getChildSecOps();
        Authorizations newChildAuths = AccumuloRyaUtils.addUserAuths(childUser, childSecOps, PARENT_AUTH);
        childSecOps.changeUserAuthorizations(childUser, newChildAuths);
        String childAuthString = newChildAuths.toString();
        List<String> duplicateKeys = MergeTool.DUPLICATE_KEY_MAP.get(MRUtils.AC_AUTH_PROP);
        childConfig.set(MRUtils.AC_AUTH_PROP, childAuthString);
        for (String key : duplicateKeys) {
            childConfig.set(key, childAuthString);
        }
        AccumuloRyaUtils.printTablePretty(CHILD_TABLE_PREFIX + RdfCloudTripleStoreConstants.TBL_PO_SUFFIX, childConfig);
        AccumuloRyaUtils.printTablePretty(CHILD_TABLE_PREFIX + RdfCloudTripleStoreConstants.TBL_OSP_SUFFIX, childConfig);
        AccumuloRyaUtils.printTablePretty(CHILD_TABLE_PREFIX + RdfCloudTripleStoreConstants.TBL_SPO_SUFFIX, childConfig);

        Scanner scanner = AccumuloRyaUtils.getScanner(CHILD_TABLE_PREFIX + RdfCloudTripleStoreConstants.TBL_SPO_SUFFIX, childConfig);
        Iterator<Entry<Key, Value>> iterator = scanner.iterator();
        int count = 0;
        while (iterator.hasNext()) {
            iterator.next();
            count++;
        }
        // Make sure we have all of them in the parent.
        assertEquals(5, count);


        assertStatementInChild("Child included statement that was out of time range", 0, ryaStatementOutOfTimeRange);

        assertStatementInChild("Child missing statement 1 that was in parent", 1, ryaStatementShouldCopy1);

        assertStatementInChild("Child missing statement 2 that was in parent", 1, ryaStatementShouldCopy2);

        assertStatementInChild("Child missing statement 3 that was in parent", 1, ryaStatementShouldCopy3);

        assertStatementInChild("Child missing statement 4 that was in parent", 1, ryaStatementShouldCopy4);

        assertStatementInChild("Child included statement 1 that was not in parent", 0, ryaStatementDoesNotExist1);

        assertStatementInChild("Child included statement 2 that was not in parent", 0, ryaStatementDoesNotExist2);

        assertStatementInChild("Child included statement 3 that was not in parent", 0, ryaStatementDoesNotExist3);

        // Check that it can be queried with child's visibility
        assertStatementInChild("Child missing statement with child visibility", 1, ryaStatementVisibilityDifferent);

        // Check that it can be queried with parent's visibility
        childConfig.set(RdfCloudTripleStoreConfiguration.CONF_QUERY_AUTH, PARENT_AUTH);
        SecurityOperations secOps = IS_MOCK ? accumuloDualInstanceDriver.getChildSecOps() : childSecOps;
        newChildAuths = AccumuloRyaUtils.addUserAuths(accumuloDualInstanceDriver.getChildUser(), secOps, PARENT_AUTH);
        secOps.changeUserAuthorizations(accumuloDualInstanceDriver.getChildUser(), newChildAuths);
        assertStatementInChild("Child missing statement with parent visibility", 1, ryaStatementVisibilityDifferent);

        // Check that it can NOT be queried with some other visibility
        childConfig.set(RdfCloudTripleStoreConfiguration.CONF_QUERY_AUTH, "bad_auth");
        CloseableIteration<RyaStatement, RyaDAOException> iter = childDao.getQueryEngine().query(ryaStatementVisibilityDifferent, childConfig);
        count = 0;
        try {
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
        } catch (Exception e) {
            // Expected
            if (!(e.getCause() instanceof AccumuloSecurityException)) {
                fail();
            }
        }
        iter.close();
        assertEquals(0, count);

        // reset auth
        childConfig.set(RdfCloudTripleStoreConfiguration.CONF_QUERY_AUTH, childAuthString);

        log.info("DONE");
    }

    @Test
    public void testImportDirectoryTool() throws Exception {
        log.info("");
        log.info("Setting up initial state of parent before importing directory to child...");
        log.info("Adding data to parent...");

        log.info("Starting import directory tool. Importing all data after the specified start time: " + YESTERDAY);
        log.info("");

        isImporting = true;
        copyToolRun(YESTERDAY);


        // Import Directory Tool made child instance so hook the tables and dao into the driver.
        String childUser = accumuloDualInstanceDriver.getChildUser();
        Connector childConnector = ConfigUtils.getConnector(childConfig);
        accumuloDualInstanceDriver.getChildAccumuloInstanceDriver().setConnector(childConnector);

        accumuloDualInstanceDriver.getChildAccumuloInstanceDriver().setUpTables();

        accumuloDualInstanceDriver.getChildAccumuloInstanceDriver().setUpDao();


        // Update child config to include changes made from import directory process
        SecurityOperations childSecOps = accumuloDualInstanceDriver.getChildSecOps();
        Authorizations newChildAuths = AccumuloRyaUtils.addUserAuths(childUser, childSecOps, PARENT_AUTH);
        childSecOps.changeUserAuthorizations(childUser, newChildAuths);
        String childAuthString = newChildAuths.toString();
        List<String> duplicateKeys = MergeTool.DUPLICATE_KEY_MAP.get(MRUtils.AC_AUTH_PROP);
        childConfig.set(MRUtils.AC_AUTH_PROP, childAuthString);
        for (String key : duplicateKeys) {
            childConfig.set(key, childAuthString);
        }


        //AccumuloRyaUtils.printTablePretty(CHILD_TABLE_PREFIX + RdfCloudTripleStoreConstants.TBL_PO_SUFFIX, childConfig);
        //AccumuloRyaUtils.printTablePretty(CHILD_TABLE_PREFIX + RdfCloudTripleStoreConstants.TBL_OSP_SUFFIX, childConfig);
        AccumuloRyaUtils.printTablePretty(CHILD_TABLE_PREFIX + RdfCloudTripleStoreConstants.TBL_SPO_SUFFIX, childConfig);

        Scanner scanner = AccumuloRyaUtils.getScanner(CHILD_TABLE_PREFIX + RdfCloudTripleStoreConstants.TBL_SPO_SUFFIX, childConfig);
        Iterator<Entry<Key, Value>> iterator = scanner.iterator();
        int count = 0;
        while (iterator.hasNext()) {
            iterator.next();
            count++;
        }
        log.info("");
        log.info("Total rows imported: " + count);
        log.info("");

        assertEquals(20, count);

        log.info("DONE");
    }
}
