ALTER TABLE document_claim_snapshot ADD COLUMN question TEXT;

CREATE TABLE document_claim_report (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES account(id) ON DELETE RESTRICT,
    snapshot_id UUID NOT NULL REFERENCES document_claim_snapshot(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_document_claim_report_owner ON document_claim_report(account_id, id);
