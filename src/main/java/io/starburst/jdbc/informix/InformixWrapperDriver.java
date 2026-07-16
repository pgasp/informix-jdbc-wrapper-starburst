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
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Properties;

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
            log.info("v1.6.1 IBM IfxDriver loaded OK");
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
            return null;
        }
        String database = extractDatabase(url);
        log.info("connect() database={} url={}", database, maskUrl(url));
        Connection conn = IBM_DRIVER.connect(rewrite(url), info);
        if (conn == null) {
            log.warn("connect() → null (IBM driver rejected URL)");
            return null;
        }
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
        return 6;
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
                Statement stmt = (Statement) method.invoke(delegate, args);
                return stmt == null ? null : wrapStatement(stmt, catalogPrefix);
            }
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

        DatabaseMetaDataHandler(DatabaseMetaData delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                log.error("meta.{} FAILED", method.getName(), e.getCause());
                throw e.getCause();
            }
        }
    }
}
