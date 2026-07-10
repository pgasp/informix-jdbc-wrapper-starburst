package io.starburst.jdbc.informix;

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
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Accepts jdbc:informix: URLs and rewrites them to jdbc:informix-sqli: before
 * delegating to the IBM IfxDriver. This bypasses Starburst Enterprise's
 * BaseJdbcConfig URL validation which rejects hyphens in the JDBC sub-protocol.
 *
 * Also wraps Connection.getMetaData() to override getIdentifierQuoteString() — returning
 * a space character (the JDBC convention for "no quoting supported") so that Trino's
 * generic-jdbc generates unquoted SQL identifiers, which Informix accepts natively.
 *
 * SEP catalog-values.yaml example:
 *   connector.name=generic-jdbc
 *   generic-jdbc.driver-class=io.starburst.jdbc.informix.InformixWrapperDriver
 *   connection-url=jdbc:informix://host:port/database:INFORMIXSERVER=server_name
 */
public class InformixWrapperDriver implements Driver {

    private static final String WRAPPER_PREFIX = "jdbc:informix:";
    private static final String IBM_PREFIX = "jdbc:informix-sqli:";

    private static final Driver IBM_DRIVER;

    static {
        try {
            IBM_DRIVER = (Driver) Class.forName("com.informix.jdbc.IfxDriver")
                    .getDeclaredConstructor()
                    .newInstance();
            DriverManager.registerDriver(new InformixWrapperDriver());
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(
                    "IBM Informix JDBC driver (ifxjdbc.jar) not found in classpath: " + e.getMessage());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static String rewrite(String url) {
        if (url != null && url.startsWith(WRAPPER_PREFIX)) {
            return IBM_PREFIX + url.substring(WRAPPER_PREFIX.length());
        }
        return url;
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        Connection conn = IBM_DRIVER.connect(rewrite(url), info);
        return conn == null ? null : wrapConnection(conn);
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
        return 1;
    }

    @Override
    public boolean jdbcCompliant() {
        return true;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("java.util.logging not used");
    }

    static Connection wrapConnection(Connection conn) {
        return (Connection) Proxy.newProxyInstance(
                InformixWrapperDriver.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                new ConnectionHandler(conn));
    }

    static DatabaseMetaData wrapDatabaseMetaData(DatabaseMetaData meta) {
        return (DatabaseMetaData) Proxy.newProxyInstance(
                InformixWrapperDriver.class.getClassLoader(),
                new Class<?>[] {DatabaseMetaData.class},
                new DatabaseMetaDataHandler(meta));
    }

    static final class ConnectionHandler implements InvocationHandler {
        private final Connection delegate;

        ConnectionHandler(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("getMetaData".equals(method.getName()) && (args == null || args.length == 0)) {
                return wrapDatabaseMetaData(delegate.getMetaData());
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
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
            // Return a space (JDBC convention: "no quoting") so Trino generates unquoted SQL
            // identifiers that Informix accepts natively, instead of double-quoted ones.
            if ("getIdentifierQuoteString".equals(method.getName()) && (args == null || args.length == 0)) {
                return " ";
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
