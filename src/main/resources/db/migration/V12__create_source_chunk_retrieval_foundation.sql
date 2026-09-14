-- Chunk configuration is keyed by an immutable, caller-owned version.
-- Keeping the JSON in a separate row prevents the same version from
-- silently acquiring different metadata across chunk sequences/documents.
CREATE TABLE source_chunk_configuration (
    config_version VARCHAR(100) PRIMARY KEY,
    config_json TEXT NOT NULL,
    config_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_source_chunk_configuration_version
        CHECK (length(btrim(config_version)) > 0),

    CONSTRAINT ck_source_chunk_configuration_json
        CHECK (jsonb_typeof(config_json::jsonb) = 'object'),

    CONSTRAINT ck_source_chunk_configuration_sha256
        CHECK (config_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE source_chunk (
    id UUID PRIMARY KEY,
    source_document_id UUID NOT NULL,
    chunk_config_version VARCHAR(100) NOT NULL,
    sequence INTEGER NOT NULL,
    body TEXT NOT NULL,
    body_sha256 VARCHAR(64) NOT NULL,

    parent_section TEXT,
    article_reference VARCHAR(255),
    page_reference VARCHAR(100),
    locator TEXT,

    -- Java UTF-16 offsets into source_document.normalized_content.
    source_start_offset INTEGER NOT NULL,
    source_end_offset INTEGER NOT NULL,

    -- Generic per-chunk representation/tokenizer metadata.  It is not
    -- tied to a particular semantic retrieval implementation.
    representation_metadata_json TEXT NOT NULL DEFAULT '{}',

    review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lock_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_source_chunk_document
        FOREIGN KEY (source_document_id)
        REFERENCES source_document (id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_source_chunk_configuration
        FOREIGN KEY (chunk_config_version)
        REFERENCES source_chunk_configuration (config_version)
        ON DELETE RESTRICT,

    CONSTRAINT uk_source_chunk_document_config_sequence
        UNIQUE (source_document_id, chunk_config_version, sequence),

    CONSTRAINT ck_source_chunk_sequence
        CHECK (sequence >= 0),

    CONSTRAINT ck_source_chunk_body
        CHECK (length(btrim(body)) > 0),

    CONSTRAINT ck_source_chunk_body_sha256
        CHECK (body_sha256 ~ '^[0-9a-f]{64}$'),

    CONSTRAINT ck_source_chunk_offsets
        CHECK (
            source_start_offset >= 0
            AND source_end_offset > source_start_offset
        ),

    CONSTRAINT ck_source_chunk_representation_metadata_json
        CHECK (jsonb_typeof(representation_metadata_json::jsonb) = 'object'),

    CONSTRAINT ck_source_chunk_review_status
        CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED')),

    CONSTRAINT ck_source_chunk_reviewed_at
        CHECK (
            (review_status = 'PENDING' AND reviewed_at IS NULL)
            OR
            (review_status IN ('APPROVED', 'REJECTED') AND reviewed_at IS NOT NULL)
        )
);

CREATE INDEX idx_source_chunk_document
    ON source_chunk (source_document_id, sequence);

CREATE INDEX idx_source_chunk_review
    ON source_chunk (review_status);

CREATE TABLE retrieval_generation (
    id UUID PRIMARY KEY,
    generation_key VARCHAR(128) NOT NULL,
    representation_config_version VARCHAR(100) NOT NULL,
    model_identifier TEXT NOT NULL,
    model_revision TEXT NOT NULL,
    tokenizer_identifier TEXT NOT NULL,
    tokenizer_revision TEXT NOT NULL,
    encoding_config_json TEXT NOT NULL,
    index_config_json TEXT NOT NULL,
    metadata_json TEXT NOT NULL,

    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    ready_chunk_count INTEGER NOT NULL DEFAULT 0,
    failure_reason TEXT,
    ready_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lock_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uk_retrieval_generation_key
        UNIQUE (generation_key),

    CONSTRAINT ck_retrieval_generation_key
        CHECK (length(btrim(generation_key)) > 0),

    CONSTRAINT ck_retrieval_generation_representation_version
        CHECK (length(btrim(representation_config_version)) > 0),

    CONSTRAINT ck_retrieval_generation_model_identifier
        CHECK (length(btrim(model_identifier)) > 0),

    CONSTRAINT ck_retrieval_generation_model_revision
        CHECK (length(btrim(model_revision)) > 0),

    CONSTRAINT ck_retrieval_generation_tokenizer_identifier
        CHECK (length(btrim(tokenizer_identifier)) > 0),

    CONSTRAINT ck_retrieval_generation_tokenizer_revision
        CHECK (length(btrim(tokenizer_revision)) > 0),

    CONSTRAINT ck_retrieval_generation_encoding_config_json
        CHECK (jsonb_typeof(encoding_config_json::jsonb) = 'object'),

    CONSTRAINT ck_retrieval_generation_index_config_json
        CHECK (jsonb_typeof(index_config_json::jsonb) = 'object'),

    CONSTRAINT ck_retrieval_generation_metadata_json
        CHECK (jsonb_typeof(metadata_json::jsonb) = 'object'),

    CONSTRAINT ck_retrieval_generation_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')),

    CONSTRAINT ck_retrieval_generation_ready_count
        CHECK (ready_chunk_count >= 0),

    CONSTRAINT ck_retrieval_generation_ready_state
        CHECK (
            (status = 'READY' AND ready_chunk_count > 0 AND ready_at IS NOT NULL)
            OR
            (status <> 'READY')
        )
);

CREATE INDEX idx_retrieval_generation_status
    ON retrieval_generation (status);

CREATE TABLE source_chunk_indexing (
    id UUID PRIMARY KEY,
    source_chunk_id UUID NOT NULL,
    retrieval_generation_id UUID NOT NULL,

    -- This is the external index document identity.  It is always the
    -- stable SourceChunk UUID, rendered as a string by an external runtime.
    external_document_id UUID NOT NULL,

    indexing_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    index_metadata_json TEXT NOT NULL DEFAULT '{}',
    failure_reason TEXT,
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lock_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_source_chunk_indexing_chunk
        FOREIGN KEY (source_chunk_id)
        REFERENCES source_chunk (id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_source_chunk_indexing_generation
        FOREIGN KEY (retrieval_generation_id)
        REFERENCES retrieval_generation (id)
        ON DELETE RESTRICT,

    CONSTRAINT uk_source_chunk_indexing_chunk_generation
        UNIQUE (source_chunk_id, retrieval_generation_id),

    CONSTRAINT uk_source_chunk_indexing_generation_external_id
        UNIQUE (retrieval_generation_id, external_document_id),

    CONSTRAINT ck_source_chunk_indexing_status
        CHECK (indexing_status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')),

    CONSTRAINT ck_source_chunk_indexing_metadata_json
        CHECK (jsonb_typeof(index_metadata_json::jsonb) = 'object'),

    CONSTRAINT ck_source_chunk_indexing_processed_at
        CHECK (
            (indexing_status IN ('PENDING', 'PROCESSING') AND processed_at IS NULL)
            OR
            (indexing_status IN ('READY', 'FAILED') AND processed_at IS NOT NULL)
        )
);

CREATE INDEX idx_source_chunk_indexing_generation_status
    ON source_chunk_indexing (retrieval_generation_id, indexing_status);

CREATE INDEX idx_source_chunk_indexing_chunk
    ON source_chunk_indexing (source_chunk_id);
