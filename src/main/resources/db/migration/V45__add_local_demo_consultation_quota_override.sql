-- A local demo override never changes consultation_start history or the production limit.
CREATE TABLE local_demo_consultation_quota_override (
    account_id UUID PRIMARY KEY REFERENCES account(id) ON DELETE RESTRICT,
    new_consultation_limit INTEGER NOT NULL CHECK (new_consultation_limit BETWEEN 5 AND 100),
    expires_at TIMESTAMPTZ NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE local_demo_consultation_quota_audit (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES account(id) ON DELETE RESTRICT,
    previous_limit INTEGER,
    new_limit INTEGER NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    operator_note VARCHAR(150) NOT NULL CHECK (length(btrim(operator_note)) > 0)
);
