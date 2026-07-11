package uz.greenwhite.gateway.admin;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class AdminAccountRepository {

    private final JdbcTemplate jdbc;

    public AdminAccountRepository(@Qualifier("settingsJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AdminAccount> MAPPER = (rs, n) -> AdminAccount.builder()
            .id(rs.getLong("id"))
            .username(rs.getString("username"))
            .passwordHash(rs.getString("password_hash"))
            .lastLoginAt(rs.getObject("last_login_at", Timestamp.class) != null
                    ? rs.getTimestamp("last_login_at").toLocalDateTime() : null)
            .build();

    public int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_account", Integer.class);
    }

    public Optional<AdminAccount> findByUsername(String username) {
        return jdbc.query("SELECT * FROM admin_account WHERE username = ?", MAPPER, username)
                .stream().findFirst();
    }

    public void insert(String username, String passwordHash) {
        jdbc.update("INSERT INTO admin_account (username, password_hash) VALUES (?, ?)",
                username, passwordHash);
    }

    public void updatePassword(String username, String passwordHash) {
        jdbc.update("UPDATE admin_account SET password_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE username = ?",
                passwordHash, username);
    }

    public void updateLastLogin(String username) {
        jdbc.update("UPDATE admin_account SET last_login_at = CURRENT_TIMESTAMP WHERE username = ?", username);
    }
}
