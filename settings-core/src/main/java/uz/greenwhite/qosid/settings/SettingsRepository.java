package uz.greenwhite.qosid.settings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Settings table access. Plain POJO over a caller-supplied {@link JdbcTemplate} —
 * the gateway wires it against its pooled settings DataSource, the ops sidecar
 * against its own connection to the same shared H2 DB.
 */
public class SettingsRepository {

    private final JdbcTemplate jdbc;

    public SettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<SettingsRecord> MAPPER = (rs, n) -> SettingsRecord.builder()
            .key(rs.getString("skey"))
            .value(rs.getString("svalue"))
            .secret(rs.getBoolean("secret"))
            .updatedAt(rs.getObject("updated_at", Timestamp.class) != null
                    ? rs.getTimestamp("updated_at").toLocalDateTime() : null)
            .updatedBy(rs.getString("updated_by"))
            .build();

    public List<SettingsRecord> findAll() {
        return jdbc.query("SELECT * FROM settings ORDER BY skey", MAPPER);
    }

    public Optional<SettingsRecord> find(String key) {
        return jdbc.query("SELECT * FROM settings WHERE skey = ?", MAPPER, key).stream().findFirst();
    }

    public void upsert(String key, String value, boolean secret, String changedBy) {
        jdbc.update(
                "MERGE INTO settings (skey, svalue, secret, updated_at, updated_by) " +
                "KEY (skey) VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?)",
                key, value, secret, changedBy);
    }

    public void insertAudit(String key, String oldValue, String newValue, String changedBy) {
        jdbc.update(
                "INSERT INTO settings_audit (skey, old_value, new_value, changed_by) VALUES (?, ?, ?, ?)",
                key, oldValue, newValue, changedBy);
    }

    /**
     * Latest audit rows, newest first. Quoted-lowercase aliases: H2 upper-cases
     * unquoted labels (SKEY, OLD_VALUE, ...), which would break the documented
     * lowercase JSON contract the frontend relies on.
     */
    public List<Map<String, Object>> findAudit(int limit) {
        return jdbc.queryForList(
                "SELECT skey AS \"skey\", old_value AS \"old_value\", new_value AS \"new_value\", " +
                "changed_by AS \"changed_by\", changed_at AS \"changed_at\" " +
                "FROM settings_audit ORDER BY changed_at DESC FETCH FIRST ? ROWS ONLY", limit);
    }
}
