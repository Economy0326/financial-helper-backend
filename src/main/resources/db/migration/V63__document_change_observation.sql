CREATE TABLE document_change_observation (
    source_document_id UUID PRIMARY KEY REFERENCES source_document(id) ON DELETE RESTRICT,
    document_version INTEGER NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL,
    last_result VARCHAR(16) NOT NULL CHECK (last_result IN ('UNCHANGED','CHANGED','FAILED')),
    change_detected BOOLEAN NOT NULL DEFAULT FALSE
);
