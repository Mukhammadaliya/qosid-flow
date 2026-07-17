package uz.greenwhite.gateway.settings;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class SettingsRepository {

    private final JdbcTemplate jdbc;

    public SettingsRepository(@Qualifier("settingsJdbcTemplate") JdbcTemplate jdbc) {
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
}
