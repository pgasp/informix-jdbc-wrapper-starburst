package io.starburst.jdbc.informix;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
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
    void getMinorVersion_returns0() {
        assertEquals(0, driver.getMinorVersion());
    }

    @Test
    void jdbcCompliant_returnsTrue() {
        assertTrue(driver.jdbcCompliant());
    }

    @Test
    void getParentLogger_throwsSQLFeatureNotSupportedException() {
        assertThrows(SQLFeatureNotSupportedException.class, () -> driver.getParentLogger());
    }
}
