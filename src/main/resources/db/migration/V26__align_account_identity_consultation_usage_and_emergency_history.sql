CREATE TABLE account_identity (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_account_identity_account FOREIGN KEY (account_id)
        REFERENCES account (id) ON DELETE CASCADE,
    CONSTRAINT uk_account_identity_provider_subject UNIQUE (provider, provider_subject)
);

CREATE INDEX idx_account_identity_account ON account_identity (account_id);

CREATE TABLE account_consultation_start (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    consultation_id UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_account_consultation_start_account FOREIGN KEY (account_id)
        REFERENCES account (id) ON DELETE CASCADE,
    CONSTRAINT fk_account_consultation_start_consultation FOREIGN KEY (consultation_id)
        REFERENCES consultation (id) ON DELETE CASCADE,
    CONSTRAINT uk_account_consultation_start_consultation UNIQUE (consultation_id)
);

CREATE INDEX idx_account_consultation_start_window
    ON account_consultation_start (account_id, started_at);

CREATE UNIQUE INDEX uk_consultation_one_active_per_account
    ON consultation (account_id)
    WHERE account_id IS NOT NULL
      AND status IN ('IN_PROGRESS', 'ANALYZING', 'NEEDS_MORE_INFO', 'INSUFFICIENT_INFORMATION', 'FAILED');

CREATE TABLE emergency_history (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    emergency_type VARCHAR(32) NOT NULL,
    scenario_version VARCHAR(128) NOT NULL,
    viewed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_emergency_history_account FOREIGN KEY (account_id)
        REFERENCES account (id) ON DELETE CASCADE
);

CREATE INDEX idx_emergency_history_account_viewed
    ON emergency_history (account_id, viewed_at DESC);
