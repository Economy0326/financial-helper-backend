-- ISOLATED TEST DRAFT ONLY. Not a Flyway migration; do not apply to the demo DB.
CREATE TABLE source_url_verification_attempt (
    id UUID PRIMARY KEY,
    source_document_id UUID NOT NULL REFERENCES source_document(id) ON DELETE RESTRICT,
    source_registry_id UUID NOT NULL REFERENCES source_registry(id) ON DELETE RESTRICT,
    document_version INTEGER NOT NULL CHECK (document_version > 0),
    stored_raw_sha256 VARCHAR(64) NOT NULL CHECK (stored_raw_sha256 ~ '^[0-9a-f]{64}$'),
    stored_content_sha256 VARCHAR(64) NOT NULL CHECK (stored_content_sha256 ~ '^[0-9a-f]{64}$'),
    requested_url TEXT NOT NULL, final_url TEXT,
    checked_at TIMESTAMPTZ NOT NULL, valid_until TIMESTAMPTZ NOT NULL,
    method VARCHAR(80) NOT NULL, method_version INTEGER NOT NULL CHECK (method_version > 0),
    result VARCHAR(120) NOT NULL, http_status INTEGER,
    download_succeeded BOOLEAN NOT NULL, observed_raw_sha256 VARCHAR(64),
    observed_content_sha256 VARCHAR(64), source_identity_verified BOOLEAN NOT NULL,
    failure_code VARCHAR(120), legal_version_verification_required BOOLEAN NOT NULL,
    reviewer_account_id UUID NOT NULL REFERENCES account(id) ON DELETE RESTRICT,
    CHECK (valid_until > checked_at),
    FOREIGN KEY (source_document_id, stored_raw_sha256)
        REFERENCES source_document(id, raw_sha256) ON DELETE RESTRICT,
    UNIQUE (id, source_document_id, stored_raw_sha256)
);

ALTER TABLE source_review_decision_audit ADD CONSTRAINT uq_audit_document_hash
    UNIQUE (id, source_document_id, reviewed_raw_sha256);
CREATE TABLE source_review_verification_link (
    audit_id UUID PRIMARY KEY,
    verification_id UUID NOT NULL,
    source_document_id UUID NOT NULL,
    raw_sha256 VARCHAR(64) NOT NULL,
    UNIQUE (verification_id),
    FOREIGN KEY (audit_id, source_document_id, raw_sha256)
        REFERENCES source_review_decision_audit(id, source_document_id, reviewed_raw_sha256) ON DELETE RESTRICT,
    FOREIGN KEY (verification_id, source_document_id, raw_sha256)
        REFERENCES source_url_verification_attempt(id, source_document_id, stored_raw_sha256) ON DELETE RESTRICT
);

-- Independently authorized search window; never derived from URL verification freshness.
CREATE TABLE source_review_search_window (
    audit_id UUID PRIMARY KEY REFERENCES source_review_verification_link(audit_id) ON DELETE RESTRICT,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    CHECK (valid_until > valid_from)
);

-- A separately reviewed official support claim, not a URL match or a second copy
-- of the target claim, must establish that the target is current guidance.
-- Draft only: no endpoint provisions or consumes these rows yet.
ALTER TABLE source_review_decision_audit ADD CONSTRAINT uq_audit_review_decision
    UNIQUE (id, review_id, decision);
CREATE TABLE source_current_guidance_basis (
    id UUID PRIMARY KEY,
    target_document_id UUID NOT NULL,
    target_chunk_id UUID NOT NULL,
    target_raw_sha256 VARCHAR(64) NOT NULL,
    proof_review_id UUID NOT NULL,
    proof_audit_id UUID NOT NULL,
    proof_decision VARCHAR(16) NOT NULL DEFAULT 'APPROVED' CHECK (proof_decision='APPROVED'),
    proof_chunk_id UUID NOT NULL,
    proof_role VARCHAR(16) NOT NULL DEFAULT 'SUPPORT' CHECK (proof_role='SUPPORT'),
    proof_locator TEXT NOT NULL CHECK (length(btrim(proof_locator)) > 0),
    evidence_kind VARCHAR(40) NOT NULL CHECK (evidence_kind='OFFICIAL_CURRENT_GUIDANCE'),
    observed_at TIMESTAMPTZ NOT NULL,
    usable_until TIMESTAMPTZ NOT NULL CHECK (usable_until > observed_at),
    FOREIGN KEY (target_document_id, target_raw_sha256)
        REFERENCES source_document(id, raw_sha256) ON DELETE RESTRICT,
    FOREIGN KEY (target_chunk_id, target_document_id)
        REFERENCES source_chunk(id, source_document_id) ON DELETE RESTRICT,
    FOREIGN KEY (proof_audit_id, proof_review_id, proof_decision)
        REFERENCES source_review_decision_audit(id, review_id, decision) ON DELETE RESTRICT,
    FOREIGN KEY (proof_review_id, proof_chunk_id, proof_role)
        REFERENCES source_applicability_chunk(review_id, source_chunk_id, evidence_role) ON DELETE RESTRICT,
    CHECK (target_chunk_id <> proof_chunk_id)
);

CREATE FUNCTION reject_source_verification_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'source verification evidence is immutable'; END $$;
CREATE TRIGGER source_verification_immutable BEFORE UPDATE OR DELETE ON source_url_verification_attempt
    FOR EACH ROW EXECUTE FUNCTION reject_source_verification_mutation();
CREATE TRIGGER source_verification_link_immutable BEFORE UPDATE OR DELETE ON source_review_verification_link
    FOR EACH ROW EXECUTE FUNCTION reject_source_verification_mutation();
CREATE TRIGGER source_review_search_window_immutable BEFORE UPDATE OR DELETE ON source_review_search_window
    FOR EACH ROW EXECUTE FUNCTION reject_source_verification_mutation();
CREATE TRIGGER source_current_guidance_basis_immutable BEFORE UPDATE OR DELETE ON source_current_guidance_basis
    FOR EACH ROW EXECUTE FUNCTION reject_source_verification_mutation();
