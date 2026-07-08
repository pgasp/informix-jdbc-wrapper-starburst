package com.informix.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Test stub for the IBM Informix JDBC driver. Records the last URL passed to connect()
 * so tests can assert on URL rewriting without a real Informix server.
 */
public class IfxDriver implements Driver {

    public static volatile String lastConnectedUrl = null;

    static {
        try {
            DriverManager.registerDriver(new IfxDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        lastConnectedUrl = url;
        return null;
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return url != null && url.startsWith("jdbc:informix-sqli:");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return new DriverPropertyInfo[0];
    }

    @Override public int getMajorVersion() { return 4; }
    @Override public int getMinorVersion() { return 10; }
    @Override public boolean jdbcCompliant() { return true; }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
