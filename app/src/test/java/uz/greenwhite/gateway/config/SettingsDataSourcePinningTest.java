package uz.greenwhite.gateway.config;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The settings DataSource must keep at least one physical connection open at all times.
 * With H2 AUTO_SERVER, the JVM holding an open connection owns the server role; if every
 * logical close also closes the physical connection (DriverManagerDataSource behavior),
 * the server role thrashes between the gateway and the ops sidecar and concurrent access
 * (e.g. the SPA's parallel login POSTs) transiently fails with
 * "Failed to obtain JDBC Connection" / spurious 401s.
 *
 * A plain mem: URL (no DB_CLOSE_DELAY) is dropped the moment its last physical connection
 * closes — so a table surviving a logical close proves the physical connection was kept.
 */
class SettingsDataSourcePinningTest {

    @Test
    void settingsDbSurvivesLogicalConnectionClose() throws Exception {
        SettingsProperties props = new SettingsProperties();
        props.setDbPath("mem:pinning-test");
        DataSource ds = new SettingsDbConfig().settingsDataSource(props);
        try {
            try (Connection c = ds.getConnection()) {
                c.createStatement().execute("CREATE TABLE pin_probe(x INT)");
            }
            try (Connection c = ds.getConnection()) {
                assertDoesNotThrow(() -> c.createStatement().execute("SELECT * FROM pin_probe"),
                        "mem DB was wiped after a logical close — the DataSource does not pin a physical connection");
            }
        } finally {
            if (ds instanceof AutoCloseable closeable) closeable.close();
        }
    }
}
