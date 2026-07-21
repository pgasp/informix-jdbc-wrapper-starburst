package io.starburst.jdbc.informix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accepts jdbc:informix: URLs and rewrites them to jdbc:informix-sqli: before
 * delegating to the IBM IfxDriver. This bypasses Starburst Enterprise's
 * BaseJdbcConfig URL validation which rejects hyphens in the JDBC sub-protocol.
 *
 * Rewrites prepareStatement SQL to strip the catalog prefix (e.g. "syn11.") that Trino
 * adds based on TABLE_CAT returned by the IBM driver metadata. Informix does not accept
 * standard 3-part dot notation (catalog.schema.table) — it uses colon notation
 * (catalog:schema.table). Since the catalog is already set as the default database in
 * the connection URL, stripping the prefix is safe and produces valid Informix SQL.
 *
 * Starburst hardcodes double-quote as the identifier quote character in GenericJdbcClient,
 * so both unquoted (syn11.) and double-quoted ("syn11".) catalog prefixes are stripped.
 *
 * SEP catalog-values.yaml example:
 *   connector.name=generic-jdbc
 *   generic-jdbc.driver-class=io.starburst.jdbc.informix.InformixWrapperDriver
 *   connection-url=jdbc:informix://host:port/database:INFORMIXSERVER=server_name
 */
public class InformixWrapperDriver implements Driver {

    private static final Logger log = LoggerFactory.getLogger(InformixWrapperDriver.class);

    private static final String WRAPPER_PREFIX = "jdbc:informix:";
    private static final String IBM_PREFIX = "jdbc:informix-sqli:";

    private static final Driver IBM_DRIVER;

    static {
        try {
            IBM_DRIVER = (Driver) Class.forName("com.informix.jdbc.IfxDriver")
                    .getDeclaredConstructor()
                    .newInstance();
            DriverManager.registerDriver(new InformixWrapperDriver());
            log.info("v1.7.6 IBM IfxDriver loaded OK");
        } catch (ClassNotFoundException e) {
            log.error("FATAL: IBM IfxDriver not found in classpath: {}", e.getMessage());
            throw new ExceptionInInitializerError(
                    "IBM Informix JDBC driver (ifxjdbc.jar) not found in classpath: " + e.getMessage());
        } catch (Exception e) {
            log.error("FATAL: init failed", e);
            throw new ExceptionInInitializerError(e);
        }
    }

    static String rewrite(String url) {
        if (url != null && url.startsWith(WRAPPER_PREFIX)) {
            return IBM_PREFIX + url.substring(WRAPPER_PREFIX.length());
        }
        return url;
    }

    /**
     * Extracts the default database name from the connection URL.
     *
     * Supports two formats:
     *   URL format:      jdbc:informix://host:port/database:INFORMIXSERVER=...
     *   Property format: jdbc:informix:Server=host;Port=port;Database=database;...
     */
    static String extractDatabase(String url) {
        if (url == null || !url.startsWith(WRAPPER_PREFIX)) {
            return null;
        }
        String rest = url.substring(WRAPPER_PREFIX.length());

        if (rest.startsWith("//")) {
            // URL format: //host:port/database:INFORMIXSERVER=...
            // colonIdx > 0 guards against empty database segment (e.g. //:INFORMIXSERVER=...)
            int slashIdx = rest.indexOf('/', 2);
            if (slashIdx >= 0) {
                String dbPart = rest.substring(slashIdx + 1);
                int colonIdx = dbPart.indexOf(':');
                if (colonIdx > 0) {
                    return dbPart.substring(0, colonIdx);
                }
            }
            // Fall through: database may be in a Database= property appended after INFORMIXSERVER=
        }

        // Property fallback — works for both pure property-format URLs and path-format URLs
        // where Database= is specified as a semicolon-separated parameter
        // (e.g. jdbc:informix://host:port/:INFORMIXSERVER=srv;Database=syn11)
        for (String part : rest.split(";")) {
            if (part.toLowerCase().startsWith("database=")) {
                return part.substring("database=".length());
            }
        }
        return null;
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            log.debug("connect() rejected (not our prefix): {}", maskUrl(url));
            return null;
        }
        String database = extractDatabase(url);
        log.info("connect() database={} url={}", database, maskUrl(url));
        Connection conn = IBM_DRIVER.connect(rewrite(url), info);
        if (conn == null) {
            log.warn("connect() → null (IBM driver rejected URL after rewrite)");
            return null;
        }
        log.debug("connect() IBM driver OK, wrapping connection with catalogPrefix={}", database != null ? database + "." : "none");
        return wrapConnection(conn, database);
    }

    private static String maskUrl(String url) {
        if (url == null) return null;
        return url.replaceAll("(?i)(password=)[^;:&]+", "$1***");
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return url != null && url.startsWith(WRAPPER_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return IBM_DRIVER.getPropertyInfo(rewrite(url), info);
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 7;
    }

    @Override
    public boolean jdbcCompliant() {
        return true;
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("java.util.logging not used");
    }

    static Connection wrapConnection(Connection conn, String database) {
        return (Connection) Proxy.newProxyInstance(
                InformixWrapperDriver.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                new ConnectionHandler(conn, database));
    }

    static DatabaseMetaData wrapDatabaseMetaData(DatabaseMetaData meta) {
        return (DatabaseMetaData) Proxy.newProxyInstance(
                InformixWrapperDriver.class.getClassLoader(),
                new Class<?>[] {DatabaseMetaData.class},
                new DatabaseMetaDataHandler(meta));
    }

    // Builds an in-memory ResultSet that satisfies DatabaseMetaData.getColumns() column contract.
    // Only the columns that BaseJdbcClient reads are mapped; unknown keys return null/0.
    static ResultSet syntheticColumnsResultSet(String[][] rows) {
        int[] cursor = {-1};
        return (ResultSet) Proxy.newProxyInstance(
                InformixWrapperDriver.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "next":    return ++cursor[0] < rows.length;
                        case "close":   return null;
                        case "wasNull": return Boolean.FALSE;
                        case "getString": {
                            int idx = colIdx(args[0]);
                            return idx >= 0 ? rows[cursor[0]][idx] : null;
                        }
                        case "getInt": {
                            int idx = colIdx(args[0]);
                            return idx >= 0 ? Integer.parseInt(rows[cursor[0]][idx]) : 0;
                        }
                        default:
                            throw new UnsupportedOperationException("syntheticColumnsResultSet: " + method.getName());
                    }
                });
    }

    // Trino escapes table/schema names for JDBC LIKE pattern matching before passing them
    // to DatabaseMetaData.getColumns(). Informix uses backslash as escape character, so
    // underscores and percent signs become "\\_" and "\\%". Our system catalog queries use
    // exact match (=), not LIKE, so we strip the escape sequences before querying systables.
    static String unescapeMetadataPattern(String pattern) {
        return pattern == null ? null : pattern.replaceAll("\\\\([_%])", "$1");
    }

    private static int colIdx(Object key) {
        if (key instanceof String) {
            switch (((String) key).toUpperCase()) {
                case "COLUMN_NAME":      return 0;
                case "DATA_TYPE":        return 1;
                case "TYPE_NAME":        return 2;
                case "COLUMN_SIZE":      return 3;
                case "DECIMAL_DIGITS":   return 4;
                case "ORDINAL_POSITION": return 5;
                case "IS_NULLABLE":      return 6;
                case "IS_AUTOINCREMENT": return 7;
            }
        }
        return -1;
    }

    static ResultSet wrapGetTablesResultSet(ResultSet rs) {
        return (ResultSet) Proxy.newProxyInstance(
                InformixWrapperDriver.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                new GetTablesResultSetHandler(rs));
    }

    static Statement wrapStatement(Statement stmt, String catalogPrefix) {
        return (Statement) Proxy.newProxyInstance(
                InformixWrapperDriver.class.getClassLoader(),
                new Class<?>[] {Statement.class},
                new StatementHandler(stmt, catalogPrefix));
    }

    static final class ConnectionHandler implements InvocationHandler {
        private final Connection delegate;
        private final String catalogPrefix; // e.g. "syn11." — null if database not in URL

        ConnectionHandler(Connection delegate, String database) {
            this.delegate = delegate;
            this.catalogPrefix = (database != null && !database.isEmpty()) ? database + "." : null;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("getMetaData".equals(name) && (args == null || args.length == 0)) {
                log.debug("Connection.getMetaData() → wrapping DatabaseMetaData");
                return wrapDatabaseMetaData(delegate.getMetaData());
            }
            // Strip catalog prefix from SQL: Trino generates "syn11.schema.table" based on
            // TABLE_CAT returned by IBM metadata, but Informix only accepts "schema.table"
            // (colon notation "syn11:schema.table" is valid but not generated by Trino).
            if ("prepareStatement".equals(name) && args != null && args.length > 0
                    && catalogPrefix != null && args[0] instanceof String) {
                args = rewriteSqlArg(args, catalogPrefix, "prepareStatement");
            }
            // DBeaver uses Statement.execute(sql) directly, not PreparedStatement.
            // Intercept createStatement() and return a wrapped Statement that applies
            // the same catalog prefix rewrite to all SQL execution methods.
            if (name.startsWith("createStatement") && catalogPrefix != null) {
                log.debug("Connection.createStatement() → wrapping Statement with catalogPrefix={}", catalogPrefix);
                Statement stmt = (Statement) method.invoke(delegate, args);
                return stmt == null ? null : wrapStatement(stmt, catalogPrefix);
            }
            log.debug("Connection.{}() → delegating", name);
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                log.error("Connection.{} FAILED", name, e.getCause());
                throw e.getCause();
            }
        }
    }

    // Rewrites args[0] (SQL string) by stripping the catalog prefix.
    // Handles both unquoted (syn11.) and double-quoted ("syn11".) forms — Starburst
    // hardcodes double-quote as identifier quote in GenericJdbcClient regardless of
    // what DatabaseMetaData.getIdentifierQuoteString() returns.
    private static Object[] rewriteSqlArg(Object[] args, String catalogPrefix, String methodName) {
        String sql = (String) args[0];
        // catalogPrefix = "syn11." — strip quoted form first, then unquoted fallback
        String dbName = catalogPrefix.endsWith(".") ? catalogPrefix.substring(0, catalogPrefix.length() - 1) : catalogPrefix;
        String quotedPrefix = "\"" + dbName + "\".";
        String rewritten = sql.replace(quotedPrefix, "").replace(catalogPrefix, "");
        if (!rewritten.equals(sql)) {
            log.info("{} stripped catalog prefix '{}': {}", methodName, catalogPrefix, rewritten);
            args = args.clone();
            args[0] = rewritten;
        } else {
            log.debug("{} no catalog prefix to strip (prefix='{}') in: {}", methodName, catalogPrefix, sql);
        }
        return args;
    }

    static final class StatementHandler implements InvocationHandler {
        private final Statement delegate;
        private final String catalogPrefix;

        StatementHandler(Statement delegate, String catalogPrefix) {
            this.delegate = delegate;
            this.catalogPrefix = catalogPrefix;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            // Intercept all SQL execution methods that accept a raw SQL string as first arg.
            // DBeaver calls Statement.execute(sql) for every query it runs.
            if (catalogPrefix != null && args != null && args.length > 0 && args[0] instanceof String
                    && (name.equals("execute") || name.equals("executeQuery")
                        || name.equals("executeUpdate") || name.equals("addBatch"))) {
                args = rewriteSqlArg(args, catalogPrefix, "Statement." + name);
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                log.error("Statement.{} FAILED", name, e.getCause());
                throw e.getCause();
            }
        }
    }

    static final class DatabaseMetaDataHandler implements InvocationHandler {
        private final DatabaseMetaData delegate;
        // Cache of synonym column metadata rows, keyed by "schema|table".
        // Avoids re-executing SELECT * WHERE 1=0 on every getColumns() call for the same synonym.
        private final Map<String, String[][]> columnCache = new ConcurrentHashMap<>();

        DatabaseMetaDataHandler(DatabaseMetaData delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // getTables: expand types to include SYNONYM so Informix synonyms appear in the
            // catalog. The returned ResultSet is wrapped to remap TABLE_TYPE "SYNONYM" → "TABLE"
            // because Starburst filters the result on TABLE_TYPE and only retains TABLE/VIEW.
            if ("getTables".equals(method.getName()) && args != null && args.length == 4) {
                String[] originalTypes = (String[]) args[3];
                log.debug("meta.getTables(catalog={} schema={} table={} types={})",
                        args[0], args[1], args[2],
                        originalTypes != null ? Arrays.toString(originalTypes) : "null");
                args = expandTypesWithSynonym(args);
                log.debug("meta.getTables: expanded types={}", Arrays.toString((String[]) args[3]));
                try {
                    Object rs = method.invoke(delegate, args);
                    if (rs == null) {
                        log.warn("meta.getTables: IBM driver returned null ResultSet");
                        return null;
                    }
                    log.debug("meta.getTables: wrapping ResultSet (SYNONYM → TABLE remapping active)");
                    return wrapGetTablesResultSet((ResultSet) rs);
                } catch (InvocationTargetException e) {
                    log.error("meta.getTables FAILED", e.getCause());
                    throw e.getCause();
                }
            }
            // getColumns: resolve synonyms before delegating. The IBM driver does not follow
            // the synonym chain when returning column metadata, yielding 0 columns.
            // Two strategies, in order:
            // 1. syssyntable JOIN — works for local (same-database) synonyms
            // 2. SELECT * WHERE 1=0 fallback — for cross-database synonyms where btabid maps to
            //    a table in another database, Informix's SQL engine follows the chain but the
            //    JDBC metadata API does not. We detect this via systables.tabtype='S' and build
            //    a synthetic ResultSet from ResultSetMetaData.
            if ("getColumns".equals(method.getName()) && args != null && args.length == 4) {
                String schema = (String) args[1];
                String table  = (String) args[2];
                log.debug("meta.getColumns(catalog={} schema={} table={} colPattern={})",
                        args[0], schema, table, args[3]);
                if (table != null && !table.contains("%")) {
                    // Trino escapes the table name for JDBC LIKE pattern matching before passing
                    // it to getColumns() (e.g. "s_mode_pe" → "s\_mode\_pe"). Our system catalog
                    // queries use exact match (=), so we must un-escape first.
                    String plainTable = unescapeMetadataPattern(table);
                    if (!table.equals(plainTable)) {
                        log.debug("meta.getColumns: un-escaped table name: {} → {}", table, plainTable);
                    }
                    String[] resolved = resolveSynonymTarget(schema, plainTable);
                    if (resolved != null) {
                        log.info("meta.getColumns: local synonym {}.{} → redirecting to {}.{}",
                                schema, plainTable, resolved[0], resolved[1]);
                        args = args.clone();
                        args[1] = resolved[0];
                        args[2] = resolved[1];
                    } else {
                        log.debug("meta.getColumns: {}.{} not found in local syssyntable — checking systables", schema, plainTable);
                        if (isSynonymInSystemCatalog(schema, plainTable)) {
                            log.info("meta.getColumns: cross-database synonym {}.{} confirmed — WHERE 1=0 fallback", schema, plainTable);
                            ResultSet synthetic = buildColumnsFromQuery(schema, plainTable);
                            if (synthetic != null) return synthetic;
                            log.warn("meta.getColumns: WHERE 1=0 fallback failed for {}.{} — delegating to IBM driver (may return 0 columns)", schema, plainTable);
                        } else {
                            log.debug("meta.getColumns: {}.{} is not a synonym (tabtype!='S') — delegating normally", schema, plainTable);
                        }
                    }
                } else if (table != null) {
                    log.debug("meta.getColumns: table='{}' contains wildcard — skipping synonym resolution", table);
                }
            }
            log.debug("meta.{}() → delegating to IBM driver", method.getName());
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                log.error("meta.{} FAILED", method.getName(), e.getCause());
                throw e.getCause();
            }
        }

        private String[] resolveSynonymTarget(String schema, String table) {
            String sql = "SELECT t2.owner, t2.tabname" +
                    " FROM informix.systables t1" +
                    " JOIN informix.syssyntable ss ON t1.tabid = ss.tabid" +
                    " JOIN informix.systables t2  ON ss.btabid = t2.tabid" +
                    " WHERE t1.tabname = ? AND t1.tabtype = 'S'" +
                    (schema != null ? " AND t1.owner = ?" : "");
            log.debug("resolveSynonymTarget: querying syssyntable JOIN for {}.{}", schema, table);
            try (PreparedStatement ps = delegate.getConnection().prepareStatement(sql)) {
                ps.setString(1, table);
                if (schema != null) ps.setString(2, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String targetOwner = rs.getString("owner");
                        String targetTable = rs.getString("tabname");
                        log.debug("resolveSynonymTarget: {}.{} → {}.{} (local synonym resolved)", schema, table, targetOwner, targetTable);
                        return new String[]{targetOwner, targetTable};
                    }
                    log.debug("resolveSynonymTarget: no row found for {}.{} in syssyntable JOIN (not local, or not a synonym)", schema, table);
                }
            } catch (Exception e) {
                log.warn("resolveSynonymTarget({}.{}): query failed — {}", schema, table, e.getMessage());
            }
            return null;
        }

        // Checks systables alone (no join) — true for both local and cross-database synonyms.
        private boolean isSynonymInSystemCatalog(String schema, String table) {
            String sql = "SELECT 1 FROM informix.systables WHERE tabname = ? AND tabtype = 'S'" +
                         (schema != null ? " AND owner = ?" : "");
            log.debug("isSynonymInSystemCatalog: querying systables for {}.{} tabtype='S'", schema, table);
            try (PreparedStatement ps = delegate.getConnection().prepareStatement(sql)) {
                ps.setString(1, table);
                if (schema != null) ps.setString(2, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean found = rs.next();
                    log.debug("isSynonymInSystemCatalog: {}.{} → {}", schema, table, found ? "IS a synonym (tabtype='S')" : "NOT a synonym");
                    return found;
                }
            } catch (Exception e) {
                log.warn("isSynonymInSystemCatalog({}.{}): query failed — {}", schema, table, e.getMessage());
                return false;
            }
        }

        // Executes "SELECT * FROM ... WHERE 1=0" and builds a synthetic getColumns() ResultSet from RSMD.
        // Informix follows the synonym chain at the SQL level even for cross-database synonyms,
        // so this succeeds where DatabaseMetaData.getColumns() returns 0 rows.
        // NOTE: "SELECT FIRST 0 *" is invalid Informix syntax — FIRST requires N >= 1.
        // We use WHERE 1=0 instead to get 0 rows without a FIRST clause.
        // Results are cached in columnCache (keyed by "schema|table") so the query runs at most
        // once per synonym per DatabaseMetaData instance (i.e. per connection lifetime).
        // Three SQL forms are tried in order to handle DELIMIDENT=y and reserved owner names:
        //   1. schema.table       — standard unquoted owner.tablename
        //   2. "schema"."table"   — fully quoted (DELIMIDENT=y safe)
        //   3. table              — no schema prefix (relies on connection database context)
        private ResultSet buildColumnsFromQuery(String schema, String table) {
            String cacheKey = (schema != null ? schema : "") + "|" + table;
            String[][] cached = columnCache.get(cacheKey);
            if (cached != null) {
                log.debug("buildColumnsFromQuery: cache hit for {} ({} column(s))", cacheKey, cached.length);
                return syntheticColumnsResultSet(cached);
            }

            boolean hasSchema = schema != null && !schema.isEmpty();
            String[] candidates;
            if (hasSchema) {
                candidates = new String[]{
                    schema + "." + table,
                    "\"" + schema + "\".\"" + table + "\"",
                    table
                };
            } else {
                candidates = new String[]{ table };
            }
            for (String qualified : candidates) {
                String querySql = "SELECT * FROM " + qualified + " WHERE 1=0";
                log.debug("buildColumnsFromQuery: trying '{}'", querySql);
                try (PreparedStatement ps = delegate.getConnection().prepareStatement(querySql);
                     ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData rsmd = rs.getMetaData();
                    int count = rsmd.getColumnCount();
                    // Log full RSMD dump — catalog/schema/table fields confirm the synonym chain
                    // was followed by the SQL engine, and help diagnose type mapping issues.
                    log.debug("buildColumnsFromQuery: RSMD for '{}' — {} column(s)", qualified, count);
                    String[][] rows = new String[count][];
                    for (int i = 1; i <= count; i++) {
                        final int col = i;
                        String colName    = rsmd.getColumnName(col);
                        String colLabel   = safeGet(() -> rsmd.getColumnLabel(col));
                        int    sqlType    = rsmd.getColumnType(col);
                        String typeName   = rsmd.getColumnTypeName(col);
                        int    precision  = rsmd.getPrecision(col);
                        int    scale      = rsmd.getScale(col);
                        String displaySz  = safeGet(() -> String.valueOf(rsmd.getColumnDisplaySize(col)));
                        String nullable   = rsmd.isNullable(col) == ResultSetMetaData.columnNoNulls ? "NO" : "YES";
                        String autoInc    = rsmd.isAutoIncrement(col) ? "YES" : "NO";
                        String srcCatalog = safeGet(() -> rsmd.getCatalogName(col));
                        String srcSchema  = safeGet(() -> rsmd.getSchemaName(col));
                        String srcTable   = safeGet(() -> rsmd.getTableName(col));
                        log.debug(
                            "  col[{}] name={} label={} sqlType={} typeName={} precision={} scale={}" +
                            " displaySize={} nullable={} autoInc={} src={}.{}.{}",
                            col, colName, colLabel, sqlType, typeName, precision, scale,
                            displaySz, nullable, autoInc, srcCatalog, srcSchema, srcTable);
                        rows[col - 1] = new String[]{
                            colName, String.valueOf(sqlType), typeName,
                            String.valueOf(precision), String.valueOf(scale),
                            String.valueOf(col), nullable, autoInc
                        };
                    }
                    if (count == 0) {
                        log.warn("buildColumnsFromQuery: query succeeded but 0 columns for {} — synonym may not be accessible", qualified);
                    } else {
                        log.info("buildColumnsFromQuery: {} column(s) for {} via '{}' — caching", count, cacheKey, querySql);
                        columnCache.put(cacheKey, rows);
                    }
                    return syntheticColumnsResultSet(rows);
                } catch (Exception e) {
                    log.debug("buildColumnsFromQuery: '{}' failed — {} (trying next form)", querySql, e.getMessage());
                }
            }
            log.warn("buildColumnsFromQuery({}.{}): all SQL forms failed — synonym columns unavailable", schema, table);
            return null;
        }

        // Calls a supplier that may throw a checked exception; returns the value or "?" on error.
        // Used for optional RSMD fields (catalog/schema/table) that some drivers don't implement.
        private static String safeGet(SqlSupplier<String> s) {
            try { return s.get(); } catch (Exception e) { return "?"; }
        }

        @FunctionalInterface
        interface SqlSupplier<T> { T get() throws Exception; }

        private static Object[] expandTypesWithSynonym(Object[] args) {
            String[] types = (String[]) args[3];
            Set<String> typeSet = types != null
                    ? new LinkedHashSet<>(Arrays.asList(types))
                    : new LinkedHashSet<>(Arrays.asList("TABLE", "VIEW"));
            if (typeSet.add("SYNONYM")) {
                args = args.clone();
                args[3] = typeSet.toArray(new String[0]);
            }
            return args;
        }
    }

    static final class GetTablesResultSetHandler implements InvocationHandler {
        private final ResultSet delegate;

        GetTablesResultSetHandler(ResultSet delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                Object result = method.invoke(delegate, args);
                if ("getString".equals(method.getName()) && args != null && args.length == 1) {
                    // TABLE_TYPE is column 4 per DatabaseMetaData.getTables() JDBC spec
                    if (args[0] instanceof Integer && (Integer) args[0] == 4) {
                        return remapSynonym((String) result);
                    }
                    if (args[0] instanceof String && "TABLE_TYPE".equalsIgnoreCase((String) args[0])) {
                        return remapSynonym((String) result);
                    }
                }
                return result;
            } catch (InvocationTargetException e) {
                log.error("GetTablesResultSet.{} FAILED", method.getName(), e.getCause());
                throw e.getCause();
            }
        }

        private static String remapSynonym(String value) {
            if ("SYNONYM".equals(value)) {
                log.debug("GetTablesResultSet: TABLE_TYPE SYNONYM → TABLE");
                return "TABLE";
            }
            return value;
        }
    }
}
