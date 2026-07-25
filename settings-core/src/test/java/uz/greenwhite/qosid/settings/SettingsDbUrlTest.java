package uz.greenwhite.qosid.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsDbUrlTest {

    @Test
    void memPathsGetNoAutoServerAndNoBindAddress() {
        System.clearProperty("h2.bindAddress");
        assertEquals("", SettingsDbUrl.autoServerSuffix("mem:t;DB_CLOSE_DELAY=-1"));
        assertNull(System.getProperty("h2.bindAddress"),
                "mem: paths reject AUTO_SERVER and must not touch the bind address");
    }

    @Test
    void filePathsGetAutoServerAndLocalhostBind() {
        System.clearProperty("h2.bindAddress");
        assertEquals(";AUTO_SERVER=TRUE", SettingsDbUrl.autoServerSuffix("./data/settings"));
        // Forces the H2 server onto loopback so a multi-NIC host doesn't advertise a LAN IP
        // that the peer JVM can't reach (the ops-login 12s-timeout bug).
        assertEquals("127.0.0.1", System.getProperty("h2.bindAddress"));
    }

    @Test
    void doesNotOverrideOperatorBindAddress() {
        System.setProperty("h2.bindAddress", "10.0.0.5");
        try {
            SettingsDbUrl.autoServerSuffix("./data/settings");
            assertEquals("10.0.0.5", System.getProperty("h2.bindAddress"));
        } finally {
            System.clearProperty("h2.bindAddress");
        }
    }

    @Test
    void nullPathIsSafe() {
        assertEquals("", SettingsDbUrl.autoServerSuffix(null));
    }
}
