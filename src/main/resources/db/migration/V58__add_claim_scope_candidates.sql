-- Proposals are never a Claim approval or search entitlement.
ALTER TABLE source_document_claim ADD CONSTRAINT uk_scope_claim_document UNIQUE(candidate_id, source_document_id);
CREATE TABLE source_claim_scope_candidate (
    id UUID PRIMARY KEY,
    candidate_id VARCHAR(64) NOT NULL REFERENCES source_document_claim(candidate_id) ON DELETE RESTRICT,
    document_id UUID NOT NULL REFERENCES source_document(id) ON DELETE RESTRICT,
    document_version INTEGER NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    scope_text TEXT NOT NULL CHECK (length(btrim(scope_text)) > 0),
    evidence_kind VARCHAR(16) NOT NULL CHECK (evidence_kind IN ('TITLE','SECTION','BODY')),
    evidence_start INTEGER NOT NULL CHECK (evidence_start >= 0),
    evidence_end INTEGER NOT NULL CHECK (evidence_end > evidence_start),
    evidence_text TEXT NOT NULL CHECK (length(btrim(evidence_text)) > 0),
    evidence_sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (candidate_id, document_id) REFERENCES source_document_claim(candidate_id, source_document_id) ON DELETE RESTRICT,
    CHECK (scope_text = evidence_text),
    UNIQUE(candidate_id, evidence_kind, evidence_start, evidence_end, evidence_sha256)
);
CREATE TABLE source_claim_scope_review (
    id UUID PRIMARY KEY,
    scope_candidate_id UUID NOT NULL REFERENCES source_claim_scope_candidate(id) ON DELETE RESTRICT,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('CONFIRMED','REJECTED')),
    approval_audit_id UUID REFERENCES source_review_decision_audit(id) ON DELETE RESTRICT,
    reviewer_account_id UUID NOT NULL REFERENCES account(id) ON DELETE RESTRICT,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((decision='CONFIRMED' AND approval_audit_id IS NOT NULL) OR decision='REJECTED')
);
CREATE INDEX idx_claim_scope_candidate_claim ON source_claim_scope_candidate(candidate_id);
CREATE INDEX idx_claim_scope_review_candidate ON source_claim_scope_review(scope_candidate_id, decided_at DESC);
