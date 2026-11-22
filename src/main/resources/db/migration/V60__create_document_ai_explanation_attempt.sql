CREATE TABLE document_ai_explanation_attempt (
    request_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES account(id) ON DELETE RESTRICT,
    snapshot_id UUID NOT NULL REFERENCES document_claim_snapshot(id) ON DELETE RESTRICT,
    quota_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RESERVED', 'SUCCEEDED', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);
CREATE INDEX idx_document_ai_attempt_account_date ON document_ai_explanation_attempt(account_id, quota_date, status);
