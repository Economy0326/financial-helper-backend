CREATE TABLE document_claim_snapshot (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES account(id) ON DELETE RESTRICT,
    retrieval_generation_id UUID NOT NULL REFERENCES retrieval_generation(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_document_claim_snapshot_owner ON document_claim_snapshot(account_id, id);
CREATE TABLE document_claim_snapshot_item (
    snapshot_id UUID NOT NULL REFERENCES document_claim_snapshot(id) ON DELETE RESTRICT,
    candidate_id VARCHAR(64) NOT NULL REFERENCES source_document_claim(candidate_id) ON DELETE RESTRICT,
    review_id UUID NOT NULL REFERENCES source_applicability_review(id) ON DELETE RESTRICT,
    source_chunk_id UUID NOT NULL REFERENCES source_chunk(id) ON DELETE RESTRICT,
    source_document_id UUID NOT NULL REFERENCES source_document(id) ON DELETE RESTRICT,
    document_version INTEGER NOT NULL,
    raw_sha256 VARCHAR(64) NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    PRIMARY KEY (snapshot_id, candidate_id),
    UNIQUE (snapshot_id, position)
);
