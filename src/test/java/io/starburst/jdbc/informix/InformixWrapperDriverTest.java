package io.starburst.jdbc.informix;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class InformixWrapperDriverTest {

    private InformixWrapperDriver driver;

    @BeforeEach
    void setUp() {
        driver = new InformixWrapperDriver();
        com.informix.jdbc.IfxDriver.lastConnectedUrl = null;
    }

    // --- acceptsURL ---

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:informix://host:9088/mydb:INFORMIXSERVER=srv",
        "jdbc:informix://localhost:9088/testdb",
        "jdbc:informix:"
    })
    void acceptsURL_matchingPrefix_returnsTrue(String url) throws SQLException {
        assertTrue(driver.acceptsURL(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:informix-sqli://host:9088/mydb",
        "jdbc:postgresql://host:5432/db",
        "jdbc:mysql://host:3306/db",
        ""
    })
    void acceptsURL_nonMatchingUrl_returnsFalse(String url) throws SQLException {
        assertFalse(driver.acceptsURL(url));
    }

    @Test
    void acceptsURL_nullUrl_returnsFalse() throws SQLException {
        assertFalse(driver.acceptsURL(null));
    }

    // --- extractDatabase ---

    @Test
    void extractDatabase_urlFormat_returnsDatabase() {
        assertEquals("syn11",
                InformixWrapperDriver.extractDatabase("jdbc:informix://host:9088/syn11:INFORMIXSERVER=srv"));
    }

    @Test
    void extractDatabase_propertyFormat_returnsDatabase() {
        assertEquals("syn11",
                InformixWrapperDriver.extractDatabase("jdbc:informix:Server=host;Port=9088;Database=syn11;INFORMIXSERVER=srv"));
    }

    @Test
    void extractDatabase_urlFormatEmptyPathDatabaseInProperty_returnsDatabase() {
        // DBeaver-style URL: no database in path, Database= appended as property
        assertEquals("syn11",
                InformixWrapperDriver.extractDatabase("jdbc:informix://host:9088/:INFORMIXSERVER=srv;Database=syn11"));
    }

    @Test
    void extractDatabase_noDatabase_returnsNull() {
        assertNull(InformixWrapperDriver.extractDatabase("jdbc:informix://host:9088/:INFORMIXSERVER=srv"));
    }

    @Test
    void extractDatabase_nonMatchingUrl_returnsNull() {
        assertNull(InformixWrapperDriver.extractDatabase("jdbc:informix-sqli://host:9088/db:INFORMIXSERVER=srv"));
    }

    // --- rewrite (URL transformation) ---

    @Test
    void rewrite_informixPrefix_rewritesToInformixSqli() {
        assertEquals(
            "jdbc:informix-sqli://host:9088/mydb:INFORMIXSERVER=srv",
            InformixWrapperDriver.rewrite("jdbc:informix://host:9088/mydb:INFORMIXSERVER=srv"));
    }

    @Test
    void rewrite_preservesFullConnectionString() {
        assertEquals(
            "jdbc:informix-sqli://10.0.0.1:9088/aiel:INFORMIXSERVER=ol_informix1210",
            InformixWrapperDriver.rewrite("jdbc:informix://10.0.0.1:9088/aiel:INFORMIXSERVER=ol_informix1210"));
    }

    @Test
    void rewrite_nonMatchingUrl_returnsUnchanged() {
        String url = "jdbc:postgresql://host:5432/db";
        assertEquals(url, InformixWrapperDriver.rewrite(url));
    }

    @Test
    void rewrite_nullUrl_returnsNull() {
        assertNull(InformixWrapperDriver.rewrite(null));
    }

    // --- connect: delegation ---

    @Test
    void connect_rewritesUrlAndDelegatesToIbmDriver() throws SQLException {
        driver.connect("jdbc:informix://host:9088/mydb:INFORMIXSERVER=srv", new Properties());
        assertEquals("jdbc:informix-sqli://host:9088/mydb:INFORMIXSERVER=srv",
                com.informix.jdbc.IfxDriver.lastConnectedUrl);
    }

    @Test
    void connect_nonMatchingUrl_returnsNullWithoutDelegating() throws SQLException {
        assertNull(driver.connect("jdbc:postgresql://host:5432/db", new Properties()));
        assertNull(com.informix.jdbc.IfxDriver.lastConnectedUrl);
    }

    @Test
    void connect_nullUrl_returnsNull() throws SQLException {
        assertNull(driver.connect(null, new Properties()));
        assertNull(com.informix.jdbc.IfxDriver.lastConnectedUrl);
    }

    // --- DatabaseMetaData wrapping ---

    private static DatabaseMetaData stubMeta() {
        return (DatabaseMetaData) Proxy.newProxyInstance(
                InformixWrapperDriverTest.class.getClassLoader(),
                new Class<?>[] {DatabaseMetaData.class},
                (proxy, method, args) -> {
                    if ("getIdentifierQuoteString".equals(method.getName())) return "\"";
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void wrapConnection_getMetaData_returnsWrappedProxy() throws SQLException {
        DatabaseMetaData innerMeta = stubMeta();
        Connection mockConn = (Connection) Proxy.newProxyInstance(
                InformixWrapperDriverTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) return innerMeta;
                    throw new UnsupportedOperationException(method.getName());
                });

        Connection wrapped = InformixWrapperDriver.wrapConnection(mockConn, null);
        DatabaseMetaData meta = wrapped.getMetaData();

        assertNotSame(innerMeta, meta);
        assertInstanceOf(DatabaseMetaData.class, meta);
    }

    // --- getPropertyInfo ---

    @Test
    void getPropertyInfo_delegatesToIbmDriver() throws SQLException {
        DriverPropertyInfo[] info = driver.getPropertyInfo(
                "jdbc:informix://host:9088/mydb:INFORMIXSERVER=srv", new Properties());
        assertNotNull(info);
    }

    // --- metadata ---

    @Test
    void getMajorVersion_returns1() {
        assertEquals(1, driver.getMajorVersion());
    }

    @Test
    void getMinorVersion_returns7() {
        assertEquals(7, driver.getMinorVersion());
    }

    @Test
    void jdbcCompliant_returnsTrue() {
        assertTrue(driver.jdbcCompliant());
    }

    @Test
    void getParentLogger_throwsSQLFeatureNotSupportedException() {
        assertThrows(SQLFeatureNotSupportedException.class, () -> driver.getParentLogger());
    }

    // --- getTables: SYNONYM expansion ---

    /** Captures the types[] array passed to DatabaseMetaData.getTables(). */
    private static class CapturingMeta {
        String[] capturedTypes;

        DatabaseMetaData proxy() {
            return (DatabaseMetaData) Proxy.newProxyInstance(
                    InformixWrapperDriverTest.class.getClassLoader(),
                    new Class<?>[] {DatabaseMetaData.class},
                    (p, method, args) -> {
                        if ("getTables".equals(method.getName())) {
                            capturedTypes = (String[]) args[3];
                            // Return an empty ResultSet stub
                            return emptyResultSet();
                        }
                        if ("getIdentifierQuoteString".equals(method.getName())) return "\"";
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static ResultSet emptyResultSet() {
        return (ResultSet) Proxy.newProxyInstance(
                InformixWrapperDriverTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                (p, method, args) -> {
                    if ("next".equals(method.getName())) return Boolean.FALSE;
                    if ("close".equals(method.getName())) return null;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void getTables_nullTypes_addsSynonym() throws Exception {
        CapturingMeta cap = new CapturingMeta();
        DatabaseMetaData wrapped = InformixWrapperDriver.wrapDatabaseMetaData(cap.proxy());

        wrapped.getTables(null, null, null, null);

        List<String> types = Arrays.asList(cap.capturedTypes);
        assertTrue(types.contains("SYNONYM"), "SYNONYM should be added when types is null");
        assertTrue(types.contains("TABLE"));
        assertTrue(types.contains("VIEW"));
    }

    @Test
    void getTables_explicitTableView_addsSynonym() throws Exception {
        CapturingMeta cap = new CapturingMeta();
        DatabaseMetaData wrapped = InformixWrapperDriver.wrapDatabaseMetaData(cap.proxy());

        wrapped.getTables(null, null, null, new String[]{"TABLE", "VIEW"});

        List<String> types = Arrays.asList(cap.capturedTypes);
        assertTrue(types.contains("SYNONYM"));
        assertTrue(types.contains("TABLE"));
        assertTrue(types.contains("VIEW"));
    }

    @Test
    void getTables_synonymAlreadyPresent_noDuplicate() throws Exception {
        CapturingMeta cap = new CapturingMeta();
        DatabaseMetaData wrapped = InformixWrapperDriver.wrapDatabaseMetaData(cap.proxy());

        wrapped.getTables(null, null, null, new String[]{"TABLE", "SYNONYM"});

        long count = Arrays.stream(cap.capturedTypes).filter("SYNONYM"::equals).count();
        assertEquals(1, count, "SYNONYM should not be duplicated");
    }

    // --- GetTablesResultSet: TABLE_TYPE remapping ---

    /** Builds a ResultSet that returns the given TABLE_TYPE value. */
    private static ResultSet stubResultSetWithTableType(String tableType) {
        return (ResultSet) Proxy.newProxyInstance(
                InformixWrapperDriverTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                (p, method, args) -> {
                    if ("getString".equals(method.getName()) && args != null && args.length == 1) {
                        if (args[0] instanceof Integer && (Integer) args[0] == 4) return tableType;
                        if (args[0] instanceof String && "TABLE_TYPE".equalsIgnoreCase((String) args[0])) return tableType;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void getTablesResultSet_synonymByIndex_remappedToTable() throws Exception {
        ResultSet wrapped = InformixWrapperDriver.wrapGetTablesResultSet(stubResultSetWithTableType("SYNONYM"));
        assertEquals("TABLE", wrapped.getString(4));
    }

    @Test
    void getTablesResultSet_synonymByName_remappedToTable() throws Exception {
        ResultSet wrapped = InformixWrapperDriver.wrapGetTablesResultSet(stubResultSetWithTableType("SYNONYM"));
        assertEquals("TABLE", wrapped.getString("TABLE_TYPE"));
    }

    @Test
    void getTablesResultSet_tableType_unchanged() throws Exception {
        ResultSet wrapped = InformixWrapperDriver.wrapGetTablesResultSet(stubResultSetWithTableType("TABLE"));
        assertEquals("TABLE", wrapped.getString(4));
        assertEquals("TABLE", wrapped.getString("TABLE_TYPE"));
    }

    @Test
    void getTablesResultSet_viewType_unchanged() throws Exception {
        ResultSet wrapped = InformixWrapperDriver.wrapGetTablesResultSet(stubResultSetWithTableType("VIEW"));
        assertEquals("VIEW", wrapped.getString(4));
    }

    // --- getColumns: synonym resolution ---

    /**
     * Builds a mock DatabaseMetaData that:
     *  - captures args passed to getColumns()
     *  - returns a Connection whose prepareStatement() resolves the given synonym
     */
    private static class CapturingMetaForColumns {
        String capturedSchema;
        String capturedTable;
        // null = no synonym in catalog (regular table)
        final String synonName;
        final String resolvedOwner;
        final String resolvedTable;

        CapturingMetaForColumns(String synonName, String resolvedOwner, String resolvedTable) {
            this.synonName = synonName;
            this.resolvedOwner = resolvedOwner;
            this.resolvedTable = resolvedTable;
        }

        DatabaseMetaData proxy() {
            CapturingMetaForColumns self = this;
            return (DatabaseMetaData) Proxy.newProxyInstance(
                    InformixWrapperDriverTest.class.getClassLoader(),
                    new Class<?>[] {DatabaseMetaData.class},
                    (p, method, args) -> {
                        if ("getColumns".equals(method.getName())) {
                            self.capturedSchema = (String) args[1];
                            self.capturedTable  = (String) args[2];
                            return emptyResultSet();
                        }
                        if ("getConnection".equals(method.getName())) {
                            return synonymCatalogConnection(self.synonName, self.resolvedOwner, self.resolvedTable);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    /** Connection whose prepareStatement returns synonym resolution results. */
    private static Connection synonymCatalogConnection(String synonName, String resolvedOwner, String resolvedTable) {
        return (Connection) Proxy.newProxyInstance(
                InformixWrapperDriverTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (p, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        return synonymResolvingPs(synonName, resolvedOwner, resolvedTable);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** PreparedStatement that returns one row [resolvedOwner, resolvedTable] when synonName matches. */
    private static PreparedStatement synonymResolvingPs(String synonName, String resolvedOwner, String resolvedTable) {
        String[] boundParams = new String[3]; // index 1 and 2
        return (PreparedStatement) Proxy.newProxyInstance(
                InformixWrapperDriverTest.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (p, method, args) -> {
                    if ("setString".equals(method.getName())) {
                        boundParams[(int) args[0]] = (String) args[1];
                        return null;
                    }
                    if ("executeQuery".equals(method.getName())) {
                        boolean matches = synonName != null && synonName.equals(boundParams[1]);
                        return matches
                                ? singleRowResultSet(resolvedOwner, resolvedTable)
                                : emptyResultSet();
                    }
                    if ("close".equals(method.getName())) return null;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** ResultSet with one row returning [owner=resolvedOwner, tabname=resolvedTable]. */
    private static ResultSet singleRowResultSet(String owner, String tabname) {
        boolean[] consumed = {false};
        return (ResultSet) Proxy.newProxyInstance(
                InformixWrapperDriverTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                (p, method, args) -> {
                    if ("next".equals(method.getName())) {
                        if (!consumed[0]) { consumed[0] = true; return Boolean.TRUE; }
                        return Boolean.FALSE;
                    }
                    if ("getString".equals(method.getName()) && args[0] instanceof String) {
                        if ("owner".equals(args[0]))   return owner;
                        if ("tabname".equals(args[0])) return tabname;
                    }
                    if ("close".equals(method.getName())) return null;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void getColumns_synonym_redirectsToUnderlyingTable() throws Exception {
        CapturingMetaForColumns cap = new CapturingMetaForColumns("s_mode_pe", "informix", "pe_mode");
        DatabaseMetaData wrapped = InformixWrapperDriver.wrapDatabaseMetaData(cap.proxy());

        wrapped.getColumns(null, "informix", "s_mode_pe", null);

        assertEquals("informix", cap.capturedSchema, "schema should be resolved owner");
        assertEquals("pe_mode",  cap.capturedTable,  "table should be resolved tabname");
    }

    @Test
    void getColumns_regularTable_passesThrough() throws Exception {
        // synonName=null → no synonym found → args unchanged
        CapturingMetaForColumns cap = new CapturingMetaForColumns(null, null, null);
        DatabaseMetaData wrapped = InformixWrapperDriver.wrapDatabaseMetaData(cap.proxy());

        wrapped.getColumns(null, "inf11adm", "cotisants", null);

        assertEquals("inf11adm",  cap.capturedSchema);
        assertEquals("cotisants", cap.capturedTable);
    }

    @Test
    void getColumns_wildcardTable_skipsResolution() throws Exception {
        // Table pattern with % must not trigger synonym resolution
        CapturingMetaForColumns cap = new CapturingMetaForColumns("s_%", "informix", "pe_mode");
        DatabaseMetaData wrapped = InformixWrapperDriver.wrapDatabaseMetaData(cap.proxy());

        wrapped.getColumns(null, "informix", "s_%", null);

        // Args must pass through unchanged — no resolution attempted
        assertEquals("informix", cap.capturedSchema);
        assertEquals("s_%",      cap.capturedTable);
    }

    @Test
    void getColumns_noSchemaFilter_resolvesWithoutOwnerClause() throws Exception {
        // schema=null → query without AND t1.owner=? clause
        CapturingMetaForColumns cap = new CapturingMetaForColumns("s_mode_pe", "informix", "pe_mode");
        DatabaseMetaData wrapped = InformixWrapperDriver.wrapDatabaseMetaData(cap.proxy());

        wrapped.getColumns(null, null, "s_mode_pe", null);

        assertEquals("informix", cap.capturedSchema);
        assertEquals("pe_mode",  cap.capturedTable);
    }

    // --- StatementHandler (DBeaver path) ---

    /** Builds a mock Statement that captures the last SQL passed to execute(). */
    private static class CapturingStatement {
        String lastSql;

        Statement proxy() {
            return (Statement) Proxy.newProxyInstance(
                    InformixWrapperDriverTest.class.getClassLoader(),
                    new Class<?>[] {Statement.class},
                    (p, method, args) -> {
                        if (args != null && args.length > 0 && args[0] instanceof String)
                            lastSql = (String) args[0];
                        if (method.getName().equals("execute")) return Boolean.TRUE;
                        if (method.getName().equals("executeQuery")) return null;
                        if (method.getName().equals("executeUpdate")) return 0;
                        if (method.getName().equals("addBatch")) return null;
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    @Test
    void wrapStatement_execute_stripsCatalogPrefix() throws Exception {
        CapturingStatement cap = new CapturingStatement();
        Statement wrapped = InformixWrapperDriver.wrapStatement(cap.proxy(), "syn11.");

        wrapped.execute("SELECT * FROM syn11.inf11adm.acc_hva LIMIT 1");

        assertEquals("SELECT * FROM inf11adm.acc_hva LIMIT 1", cap.lastSql);
    }

    @Test
    void wrapStatement_execute_stripsQuotedCatalogPrefix() throws Exception {
        // Starburst generates double-quoted identifiers: "syn11"."inf11adm"."pf_non_exp"
        CapturingStatement cap = new CapturingStatement();
        Statement wrapped = InformixWrapperDriver.wrapStatement(cap.proxy(), "syn11.");

        wrapped.execute("SELECT \"col\" FROM \"syn11\".\"inf11adm\".\"pf_non_exp\"");

        assertEquals("SELECT \"col\" FROM \"inf11adm\".\"pf_non_exp\"", cap.lastSql);
    }

    @Test
    void wrapStatement_execute_noPrefix_unchanged() throws Exception {
        CapturingStatement cap = new CapturingStatement();
        Statement wrapped = InformixWrapperDriver.wrapStatement(cap.proxy(), "syn11.");

        wrapped.execute("SELECT 1 FROM sysmaster:informix.systables");

        assertEquals("SELECT 1 FROM sysmaster:informix.systables", cap.lastSql);
    }

    @Test
    void wrapStatement_nullCatalogPrefix_sqlPassedThrough() throws Exception {
        CapturingStatement cap = new CapturingStatement();
        Statement wrapped = InformixWrapperDriver.wrapStatement(cap.proxy(), null);

        wrapped.execute("SELECT * FROM syn11.inf11adm.acc_hva");

        assertEquals("SELECT * FROM syn11.inf11adm.acc_hva", cap.lastSql);
    }

    /** Builds a mock Connection that captures the last SQL passed to prepareStatement(). */
    private static class CapturingConnection {
        String lastPreparedSql;

        Connection proxy() {
            return (Connection) Proxy.newProxyInstance(
                    InformixWrapperDriverTest.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (p, method, args) -> {
                        if (method.getName().equals("prepareStatement")) {
                            lastPreparedSql = (String) args[0];
                            return null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    @Test
    void wrapConnection_prepareStatement_stripsUnquotedCatalogPrefix() throws Exception {
        CapturingConnection cap = new CapturingConnection();
        Connection wrapped = InformixWrapperDriver.wrapConnection(cap.proxy(), "syn11");

        wrapped.prepareStatement("SELECT * FROM syn11.inf11adm.pf_non_exp");

        assertEquals("SELECT * FROM inf11adm.pf_non_exp", cap.lastPreparedSql);
    }

    @Test
    void wrapConnection_prepareStatement_stripsQuotedCatalogPrefix() throws Exception {
        // Exact SQL generated by Starburst for IMSA: "syn11"."inf11adm"."pf_non_exp"
        CapturingConnection cap = new CapturingConnection();
        Connection wrapped = InformixWrapperDriver.wrapConnection(cap.proxy(), "syn11");

        wrapped.prepareStatement(
            "SELECT \"invariant\", \"date_paiement\" FROM \"syn11\".\"inf11adm\".\"pf_non_exp\"");

        assertEquals(
            "SELECT \"invariant\", \"date_paiement\" FROM \"inf11adm\".\"pf_non_exp\"",
            cap.lastPreparedSql);
    }

    @Test
    void wrapConnection_createStatement_returnsWrappedStatement() throws Exception {
        CapturingStatement cap = new CapturingStatement();
        Connection mockConn = (Connection) Proxy.newProxyInstance(
                InformixWrapperDriverTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (p, method, args) -> {
                    if (method.getName().equals("createStatement")) return cap.proxy();
                    throw new UnsupportedOperationException(method.getName());
                });

        Connection wrapped = InformixWrapperDriver.wrapConnection(mockConn, "syn11");
        Statement stmt = wrapped.createStatement();
        stmt.execute("SELECT * FROM syn11.inf11adm.acc_hva");

        assertEquals("SELECT * FROM inf11adm.acc_hva", cap.lastSql);
    }
}
