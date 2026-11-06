-- A document/chunk being indexed never grants applicability. Reviews are created explicitly.
ALTER TABLE source_document ADD CONSTRAINT uk_source_document_id_raw_hash UNIQUE (id, raw_sha256);
ALTER TABLE source_chunk ADD CONSTRAINT uk_source_chunk_id_document UNIQUE (id, source_document_id);

CREATE TABLE source_applicability_review (
    id UUID PRIMARY KEY,
    source_document_id UUID NOT NULL,
    reviewed_raw_sha256 VARCHAR(64) NOT NULL,
    scope_kind VARCHAR(32) NOT NULL,
    scenario VARCHAR(100) NOT NULL,
    event_type VARCHAR(100),
    product_type VARCHAR(150),
    institution_name VARCHAR(150),
    applicability_start_date DATE NOT NULL,
    applicability_end_date DATE,
    review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    condition_required BOOLEAN NOT NULL DEFAULT FALSE,
    exception_required BOOLEAN NOT NULL DEFAULT FALSE,
    conditions_reviewed BOOLEAN NOT NULL DEFAULT FALSE,
    exceptions_reviewed BOOLEAN NOT NULL DEFAULT FALSE,
    reviewed_by VARCHAR(150),
    reviewed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revoked_by VARCHAR(150),
    revocation_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_applicability_document_hash FOREIGN KEY (source_document_id, reviewed_raw_sha256)
        REFERENCES source_document (id, raw_sha256) ON DELETE RESTRICT,
    CONSTRAINT uk_applicability_review_document UNIQUE (id, source_document_id),
    CONSTRAINT ck_applicability_scope CHECK (scope_kind IN ('COMMON', 'INSTITUTION_SPECIFIC')),
    CONSTRAINT ck_applicability_institution CHECK (
        (scope_kind = 'COMMON' AND institution_name IS NULL) OR
        (scope_kind = 'INSTITUTION_SPECIFIC' AND institution_name IS NOT NULL AND length(btrim(institution_name)) > 0)),
    CONSTRAINT ck_applicability_window CHECK (applicability_end_date IS NULL OR applicability_end_date >= applicability_start_date),
    CONSTRAINT ck_applicability_status CHECK (review_status IN ('PENDING', 'APPROVED', 'REVOKED')),
    CONSTRAINT ck_applicability_reviewed CHECK (
        (review_status = 'PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL AND revoked_at IS NULL
            AND revoked_by IS NULL AND revocation_reason IS NULL) OR
        (review_status = 'APPROVED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND revoked_at IS NULL
            AND revoked_by IS NULL AND revocation_reason IS NULL
            AND conditions_reviewed AND exceptions_reviewed) OR
        (review_status = 'REVOKED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND revoked_at IS NOT NULL
            AND revoked_by IS NOT NULL AND revocation_reason IS NOT NULL AND length(btrim(revocation_reason)) > 0))
);

CREATE TABLE source_applicability_chunk (
    review_id UUID NOT NULL,
    source_document_id UUID NOT NULL,
    source_chunk_id UUID NOT NULL,
    evidence_role VARCHAR(16) NOT NULL,
    PRIMARY KEY (review_id, source_chunk_id, evidence_role),
    CONSTRAINT fk_applicability_chunk_review FOREIGN KEY (review_id, source_document_id)
        REFERENCES source_applicability_review (id, source_document_id) ON DELETE RESTRICT,
    CONSTRAINT fk_applicability_chunk_document FOREIGN KEY (source_chunk_id, source_document_id)
        REFERENCES source_chunk (id, source_document_id) ON DELETE RESTRICT,
    CONSTRAINT ck_applicability_chunk_role CHECK (evidence_role IN ('SUPPORT', 'CONDITION', 'EXCEPTION'))
);

CREATE INDEX idx_applicability_lookup ON source_applicability_review
    (scenario, scope_kind, institution_name, review_status, applicability_start_date, applicability_end_date);
CREATE INDEX idx_applicability_chunk_lookup ON source_applicability_chunk (source_chunk_id, evidence_role);
