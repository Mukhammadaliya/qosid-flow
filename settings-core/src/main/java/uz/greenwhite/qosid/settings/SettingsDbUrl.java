package uz.greenwhite.qosid.settings;

/**
 * Single source for the shared-H2 JDBC URL suffix used by both JVMs (gateway + ops).
 * <p>
 * The settings DB is opened in H2 <b>AUTO_SERVER</b> (mixed) mode so the two processes
 * can co-open one file. The JVM that wins the server role writes its own address into
 * the {@code .lock.db} file for the other JVM to connect to. On a multi-NIC host H2 picks
 * a LAN address (e.g. {@code 192.168.x.x}) there, and the peer — reaching it only over
 * loopback — times out connecting. Forcing {@code h2.bindAddress=127.0.0.1} makes the
 * server bind loopback and advertise {@code 127.0.0.1}, so the peer always connects; it
 * also keeps the settings DB off external interfaces (a security bonus).
 * <p>
 * {@code mem:} paths (tests) reject AUTO_SERVER and need no bind address.
 */
public final class SettingsDbUrl {

    private SettingsDbUrl() {}

    /**
     * @return the JDBC URL suffix to append after {@code jdbc:h2:<path>;MODE=LEGACY...}.
     *         Empty for {@code mem:} paths; {@code ;AUTO_SERVER=TRUE} otherwise (with the
     *         localhost bind-address system property set as a side effect).
     */
    public static String autoServerSuffix(String path) {
        if (path == null || path.startsWith("mem:")) {
            return "";
        }
        // Must be set before the H2 server socket is opened; idempotent, so every URL build
        // re-asserts it harmlessly. Never override an operator's explicit choice.
        if (System.getProperty("h2.bindAddress") == null) {
            System.setProperty("h2.bindAddress", "127.0.0.1");
        }
        return ";AUTO_SERVER=TRUE";
    }
}
