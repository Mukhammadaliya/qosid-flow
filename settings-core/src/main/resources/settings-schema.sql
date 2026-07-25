CREATE TABLE IF NOT EXISTS settings (
    skey        VARCHAR(200) PRIMARY KEY,
    svalue      CLOB,
    secret      BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS settings_audit (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    skey        VARCHAR(200) NOT NULL,
    old_value   CLOB,
    new_value   CLOB,
    changed_by  VARCHAR(100),
    changed_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS admin_account (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(200) NOT NULL,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS login_handoff (
    token_hash VARCHAR(64)  PRIMARY KEY,
    username   VARCHAR(100) NOT NULL,
    expires_at TIMESTAMP    NOT NULL
);
