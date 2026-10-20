-- role: VIEWER (read only), OPERATOR (proposes changes, blocks players), ADMIN (approves changes, manages admins).
-- totp_secret is NULL until the admin enrols an authenticator app; nothing else is reachable before that.
CREATE TABLE admin_user (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL UNIQUE,
    password_hash   VARCHAR(100) NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    totp_secret     VARCHAR(64),
    totp_last_step  BIGINT       NOT NULL DEFAULT 0,
    failed_attempts INT          NOT NULL DEFAULT 0,
    locked_until    DATETIME(3),
    last_login_at   DATETIME(3),
    created_at      DATETIME(3)  NOT NULL
);

-- Bet limits and the game on/off switch. The PAM connection is configured through environment variables only.
CREATE TABLE setting (
    name       VARCHAR(64)   PRIMARY KEY,
    value      VARCHAR(1024) NOT NULL,
    updated_at DATETIME(3)   NOT NULL
);

-- One line per prize position; prize_index 0 is the centre slot, the highest index the edges.
-- probability is the chance (in %) of landing on either slot of that prize position.
CREATE TABLE odds (
    rows_count  INT           NOT NULL,
    risk        TINYINT       NOT NULL,
    prize_index INT           NOT NULL,
    multiplier  DECIMAL(12,2) NOT NULL,
    probability DECIMAL(12,8) NOT NULL,
    PRIMARY KEY (rows_count, risk, prize_index)
);

CREATE TABLE player (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id  VARCHAR(128)  NOT NULL UNIQUE,
    username     VARCHAR(128),
    currency     CHAR(3)       NOT NULL,
    last_balance DECIMAL(19,2),
    blocked      BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at   DATETIME(3)   NOT NULL,
    last_seen_at DATETIME(3)   NOT NULL
);

CREATE TABLE game_session (
    id           VARCHAR(64)  PRIMARY KEY,
    player_id    BIGINT       NOT NULL,
    pam_token    VARCHAR(512) NOT NULL,
    created_at   DATETIME(3)  NOT NULL,
    expires_at   DATETIME(3)  NOT NULL,
    CONSTRAINT fk_session_player FOREIGN KEY (player_id) REFERENCES player (id),
    INDEX idx_session_player (player_id)
);

-- SHA-256 of every launch token already used: a launch URL opens exactly one session.
CREATE TABLE used_launch_token (
    token_hash CHAR(64)    PRIMARY KEY,
    used_at    DATETIME(3) NOT NULL
);

CREATE TABLE game_round (
    id          CHAR(36)      PRIMARY KEY,
    player_id   BIGINT        NOT NULL,
    session_id  VARCHAR(64)   NOT NULL,
    rows_count  INT           NOT NULL,
    risk        TINYINT       NOT NULL,
    bet         DECIMAL(19,2) NOT NULL,
    balls       INT           NOT NULL,
    total_bet   DECIMAL(19,2) NOT NULL,
    total_win   DECIMAL(19,2),
    currency    CHAR(3)       NOT NULL,
    status      VARCHAR(20)   NOT NULL,
    slots       TEXT,
    multipliers TEXT,
    error       VARCHAR(512),
    signature   CHAR(64),
    created_at  DATETIME(3)   NOT NULL,
    updated_at  DATETIME(3)   NOT NULL,
    CONSTRAINT fk_round_player FOREIGN KEY (player_id) REFERENCES player (id),
    INDEX idx_round_player (player_id, created_at),
    INDEX idx_round_created (created_at),
    INDEX idx_round_status (status)
);

CREATE TABLE wallet_transaction (
    id                CHAR(36)      PRIMARY KEY,
    round_id          CHAR(36)      NOT NULL,
    player_id         BIGINT        NOT NULL,
    type              VARCHAR(10)   NOT NULL,
    amount            DECIMAL(19,2) NOT NULL,
    status            VARCHAR(10)   NOT NULL,
    original_tx_id    CHAR(36),
    pam_reference     VARCHAR(128),
    error             VARCHAR(512),
    attempts          INT           NOT NULL DEFAULT 0,
    created_at        DATETIME(3)   NOT NULL,
    updated_at        DATETIME(3)   NOT NULL,
    CONSTRAINT fk_tx_round FOREIGN KEY (round_id) REFERENCES game_round (id),
    INDEX idx_tx_round (round_id),
    INDEX idx_tx_status (status)
);

-- Append-only. hash = SHA-256(prev_hash | username | action | details | created_at), so edits or deletions break the chain.
CREATE TABLE audit_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    username   VARCHAR(64)  NOT NULL,
    action     VARCHAR(64)  NOT NULL,
    details    TEXT,
    created_at DATETIME(3)  NOT NULL,
    prev_hash  CHAR(64)     NOT NULL,
    hash       CHAR(64)     NOT NULL,
    INDEX idx_audit_created (created_at)
);

-- Odds and bet-limit changes: proposed by one admin, applied only after a different admin approves.
CREATE TABLE change_request (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    type          VARCHAR(16)  NOT NULL,
    target        VARCHAR(32)  NOT NULL,
    payload       TEXT         NOT NULL,
    summary       TEXT         NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    requested_by  VARCHAR(64)  NOT NULL,
    requested_at  DATETIME(3)  NOT NULL,
    decided_by    VARCHAR(64),
    decided_at    DATETIME(3),
    decision_note VARCHAR(512),
    INDEX idx_change_status (status)
);

-- Tables used only by the built-in mock PAM.
CREATE TABLE mock_wallet (
    player_id  VARCHAR(128)  PRIMARY KEY,
    username   VARCHAR(128)  NOT NULL,
    currency   CHAR(3)       NOT NULL,
    balance    DECIMAL(19,2) NOT NULL,
    token      VARCHAR(128)  NOT NULL UNIQUE,
    created_at DATETIME(3)   NOT NULL
);

CREATE TABLE mock_transaction (
    transaction_id VARCHAR(64)   PRIMARY KEY,
    player_id      VARCHAR(128)  NOT NULL,
    type           VARCHAR(10)   NOT NULL,
    amount         DECIMAL(19,2) NOT NULL,
    balance_after  DECIMAL(19,2) NOT NULL,
    round_id       VARCHAR(64),
    original_tx_id VARCHAR(64),
    created_at     DATETIME(3)   NOT NULL
);
