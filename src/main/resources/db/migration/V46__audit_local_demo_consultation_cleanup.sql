CREATE TABLE local_demo_consultation_cleanup_audit (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES account(id) ON DELETE RESTRICT,
    consultation_count INTEGER NOT NULL CHECK (consultation_count > 0),
    cleaned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    backup_reference VARCHAR(300) NOT NULL
);
