package uz.greenwhite.qosid.settings;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * One-time login handoff tokens (ops → gateway session bridge). The ops sidecar
 * mints a random token for an already-authenticated operator and stores only its
 * SHA-256 hash here; the gateway exchanges the raw token for an admin session via
 * {@link #consume(String)} — found + unexpired means the row is deleted and the
 * username returned, so a token can never be redeemed twice.
 *
 * Plain POJO over a caller-supplied {@link JdbcTemplate} — used by both JVMs
 * against the same shared H2 settings DB (same pattern as {@link AdminAccountRepository}).
 */
public class LoginHandoffRepository {

    private final JdbcTemplate jdbc;

    public LoginHandoffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String tokenHash, String username, Instant expiresAt) {
        jdbc.update("INSERT INTO login_handoff (token_hash, username, expires_at) VALUES (?, ?, ?)",
                tokenHash, username, Timestamp.from(expiresAt));
    }

    /**
     * Redeems a token hash: returns the username IFF the row exists and is unexpired,
     * deleting it in the same breath (one-time semantics). The DELETE carries the same
     * unexpired guard, so under a concurrent double-submit only the caller whose DELETE
     * actually removed the row wins.
     */
    public Optional<String> consume(String tokenHash) {
        List<String> found = jdbc.query(
                "SELECT username FROM login_handoff WHERE token_hash = ? AND expires_at > CURRENT_TIMESTAMP",
                (rs, n) -> rs.getString("username"), tokenHash);
        int deleted = jdbc.update(
                "DELETE FROM login_handoff WHERE token_hash = ? AND expires_at > CURRENT_TIMESTAMP", tokenHash);
        return (found.isEmpty() || deleted == 0) ? Optional.empty() : Optional.of(found.get(0));
    }

    /** Housekeeping: drops all expired rows. Returns the number deleted. */
    public int purgeExpired() {
        return jdbc.update("DELETE FROM login_handoff WHERE expires_at <= CURRENT_TIMESTAMP");
    }

    /** SHA-256 of the raw token, lowercase hex — the ONLY form ever stored. Same util on both sides. */
    public static String sha256Hex(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // mandated by the JCA spec — unreachable
        }
    }
}
