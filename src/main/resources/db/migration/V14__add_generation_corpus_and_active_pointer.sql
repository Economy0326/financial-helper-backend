-- The B1 generation row did not yet know the chunk configuration or corpus
-- snapshot.  Nullable columns preserve any pre-existing foundation rows;
-- the B2 build boundary requires them before an external index is built.
ALTER TABLE retrieval_generation
    ADD COLUMN chunk_config_version VARCHAR(100),
    ADD COLUMN corpus_snapshot_sha256 VARCHAR(64);

ALTER TABLE retrieval_generation
    ADD CONSTRAINT ck_retrieval_generation_chunk_config_version
        CHECK (
            chunk_config_version IS NULL
            OR length(btrim(chunk_config_version)) > 0
        );

ALTER TABLE retrieval_generation
    ADD CONSTRAINT ck_retrieval_generation_corpus_snapshot_sha256
        CHECK (
            corpus_snapshot_sha256 IS NULL
            OR corpus_snapshot_sha256 ~ '^[0-9a-f]{64}$'
        );

-- Exactly one row can point to the active generation.  Updating this row in a
-- short transaction preserves the old pointer if validation or the update
-- fails.
CREATE TABLE active_retrieval_generation (
    singleton_key BOOLEAN PRIMARY KEY DEFAULT TRUE,
    retrieval_generation_id UUID NOT NULL,
    switched_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_active_retrieval_generation_singleton
        CHECK (singleton_key = TRUE),

    CONSTRAINT fk_active_retrieval_generation_generation
        FOREIGN KEY (retrieval_generation_id)
        REFERENCES retrieval_generation (id)
        ON DELETE RESTRICT,

    CONSTRAINT uk_active_retrieval_generation_generation
        UNIQUE (retrieval_generation_id)
);
