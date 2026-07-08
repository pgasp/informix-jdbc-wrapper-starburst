package io.starburst.jdbc.informix;

import java.sql.Connection;
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
 * SEP catalog-values.yaml example:
 *   connector.name=generic-jdbc
 *   generic-jdbc.driver-class=io.starburst.jdbc.informix.InformixWrapperDriver
 *   connection-url=jdbc:informix://host:port/database:INFORMIXSERVER=server_name
 */
public class InformixWrapperDriver implements Driver {

    private static final String WRAPPER_PREFIX = "jdbc:informix:";
    private static final String IBM_PREFIX = "jdbc:informix-sqli:";

    static {
        try {
            Class.forName("com.informix.jdbc.IfxDriver");
            DriverManager.registerDriver(new InformixWrapperDriver());
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(
                    "IBM Informix JDBC driver (ifxjdbc.jar) not found in classpath: " + e.getMessage());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String rewrite(String url) {
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
        return DriverManager.getConnection(rewrite(url), info);
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return url != null && url.startsWith(WRAPPER_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        Driver ibmDriver = DriverManager.getDriver(rewrite(url));
        return ibmDriver.getPropertyInfo(rewrite(url), info);
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return true;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("java.util.logging not used");
    }
}
