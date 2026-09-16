CREATE TABLE account_ai_usage (
    account_id UUID PRIMARY KEY,
    window_started_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_account_ai_usage_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT fk_account_ai_usage_account FOREIGN KEY (account_id)
        REFERENCES account (id)
);
