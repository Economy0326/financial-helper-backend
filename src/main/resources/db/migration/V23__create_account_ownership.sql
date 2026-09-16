CREATE TABLE account (
    id UUID PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    display_name VARCHAR(200),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_account_provider_subject UNIQUE (provider, provider_subject)
);

CREATE INDEX idx_account_status ON account (status);

CREATE TABLE account_session (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_account_session_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_account_session_account FOREIGN KEY (account_id)
        REFERENCES account (id)
);

CREATE INDEX idx_account_session_account ON account_session (account_id);
CREATE INDEX idx_account_session_expiry ON account_session (expires_at);

CREATE TABLE oauth_login_state (
    id UUID PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    state_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_oauth_login_state_hash UNIQUE (state_hash)
);

ALTER TABLE guest_session
    ADD COLUMN account_id UUID;

ALTER TABLE guest_session
    ADD CONSTRAINT fk_guest_session_account FOREIGN KEY (account_id)
    REFERENCES account (id);

CREATE INDEX idx_guest_session_account ON guest_session (account_id);

ALTER TABLE consultation
    ADD COLUMN account_id UUID;

ALTER TABLE consultation
    ADD CONSTRAINT fk_consultation_account FOREIGN KEY (account_id)
    REFERENCES account (id);

CREATE INDEX idx_consultation_account ON consultation (account_id);
