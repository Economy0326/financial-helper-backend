-- Preserve existing scenario/topic checks and add a distinct document-claim target.
ALTER TABLE source_applicability_review DROP CONSTRAINT ck_applicability_review_target;
ALTER TABLE source_applicability_review ADD CONSTRAINT ck_applicability_review_target CHECK (
    (review_target_kind = 'SCENARIO' AND scenario IS NOT NULL AND financial_topic IS NULL)
    OR (review_target_kind = 'TOPIC' AND scenario IS NULL AND event_type IS NULL
        AND financial_topic IN ('CARD_PAYMENTS', 'DEPOSITS_ACCOUNTS', 'LOANS_CREDIT',
            'INSURANCE', 'INVESTMENTS_SECURITIES', 'FRAUD_SECURITY', 'CONSUMER_RIGHTS_DISPUTES'))
    OR (review_target_kind = 'DOCUMENT_CLAIM' AND scenario IS NULL AND financial_topic IS NULL AND event_type IS NULL)
);

ALTER TABLE source_applicability_review DROP CONSTRAINT ck_topic_temporal_evidence;
ALTER TABLE source_applicability_review ADD CONSTRAINT ck_topic_temporal_evidence CHECK (
    (review_target_kind = 'SCENARIO' AND temporal_policy = 'EXPLICIT_EFFECTIVE' AND applicability_start_date IS NOT NULL
        AND temporal_verified_on IS NULL AND temporal_valid_until IS NULL)
    OR (review_target_kind = 'TOPIC' AND (
        (temporal_policy = 'EXPLICIT_EFFECTIVE' AND applicability_start_date IS NOT NULL)
        OR (temporal_policy = 'VERSION_EFFECTIVE' AND applicability_start_date IS NOT NULL
            AND (applicability_end_date IS NOT NULL OR (temporal_verified_on IS NOT NULL AND temporal_valid_until IS NOT NULL
                AND temporal_valid_until BETWEEN temporal_verified_on AND temporal_verified_on + 365)))
        OR (temporal_policy IN ('CONTRACT_DATE', 'HISTORICAL_FACT') AND applicability_start_date IS NOT NULL AND applicability_end_date IS NOT NULL)
        OR (temporal_policy IN ('CURRENT_GUIDANCE', 'DYNAMIC_CHECK') AND applicability_start_date IS NULL
            AND temporal_verified_on IS NOT NULL AND temporal_valid_until IS NOT NULL
            AND temporal_valid_until >= temporal_verified_on AND temporal_valid_until <= temporal_verified_on +
                CASE WHEN temporal_policy = 'DYNAMIC_CHECK' THEN 30 ELSE 365 END AND applicability_end_date IS NULL)
        OR (temporal_policy = 'HOLD' AND review_status IN ('PENDING', 'REJECTED', 'REVOKED')
            AND applicability_start_date IS NULL AND applicability_end_date IS NULL
            AND temporal_verified_on IS NULL AND temporal_valid_until IS NULL)))
    OR (review_target_kind = 'DOCUMENT_CLAIM' AND temporal_policy = 'HOLD'
        AND applicability_start_date IS NULL AND applicability_end_date IS NULL
        AND temporal_verified_on IS NULL AND temporal_valid_until IS NULL)
);

CREATE TABLE source_document_usage_review (
    id UUID PRIMARY KEY, source_document_id UUID NOT NULL, document_version INTEGER NOT NULL,
    raw_sha256 VARCHAR(64) NOT NULL, basis_url TEXT NOT NULL, allowed_scope TEXT NOT NULL,
    privacy_reviewed BOOLEAN NOT NULL, third_party_reviewed BOOLEAN NOT NULL,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('ALLOWED','HOLD','REJECTED')),
    reviewer_account_id UUID NOT NULL REFERENCES account(id) ON DELETE RESTRICT,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason TEXT NOT NULL CHECK (length(btrim(reason)) > 0),
    FOREIGN KEY (source_document_id, raw_sha256) REFERENCES source_document(id, raw_sha256) ON DELETE RESTRICT,
    CHECK (decision <> 'ALLOWED' OR (privacy_reviewed AND third_party_reviewed AND length(btrim(allowed_scope)) > 0))
);
CREATE INDEX idx_document_usage_review ON source_document_usage_review(source_document_id, decided_at DESC);

CREATE TABLE source_document_claim (
    candidate_id VARCHAR(64) PRIMARY KEY, source_document_id UUID NOT NULL,
    document_version INTEGER NOT NULL, raw_sha256 VARCHAR(64) NOT NULL, content_sha256 VARCHAR(64) NOT NULL,
    extractor_version VARCHAR(100) NOT NULL, review_id UUID NOT NULL UNIQUE,
    source_chunk_id UUID NOT NULL, context_start INTEGER NOT NULL, context_end INTEGER NOT NULL,
    hold BOOLEAN NOT NULL, hold_reason TEXT NOT NULL,
    FOREIGN KEY (source_document_id, raw_sha256) REFERENCES source_document(id, raw_sha256) ON DELETE RESTRICT,
    FOREIGN KEY (review_id, source_document_id) REFERENCES source_applicability_review(id, source_document_id) ON DELETE RESTRICT,
    FOREIGN KEY (source_chunk_id, source_document_id) REFERENCES source_chunk(id, source_document_id) ON DELETE RESTRICT,
    CHECK (candidate_id ~ '^[0-9a-f]{64}$' AND context_start >= 0 AND context_end > context_start)
);
CREATE TABLE source_document_claim_relation (
    candidate_id VARCHAR(64) NOT NULL REFERENCES source_document_claim(candidate_id) ON DELETE RESTRICT,
    role VARCHAR(16) NOT NULL CHECK (role IN ('CONDITION','EXCEPTION','DATE','NOTE')),
    start_offset INTEGER NOT NULL, end_offset INTEGER NOT NULL, ambiguous BOOLEAN NOT NULL,
    PRIMARY KEY (candidate_id, role, start_offset, end_offset),
    CHECK (start_offset >= 0 AND end_offset > start_offset)
);
