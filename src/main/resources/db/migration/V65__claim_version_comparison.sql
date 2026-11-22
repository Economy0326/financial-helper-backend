CREATE TABLE source_claim_version_comparison (
    current_document_id UUID NOT NULL REFERENCES source_document(id) ON DELETE RESTRICT,
    previous_candidate_id VARCHAR(64) NOT NULL REFERENCES source_document_claim(candidate_id) ON DELETE RESTRICT,
    current_candidate_id VARCHAR(64) REFERENCES source_document_claim(candidate_id) ON DELETE RESTRICT,
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN ('INHERITED_APPROVED','INHERITED_REJECTED','REVIEW_REQUIRED','NOT_IN_LATEST_EXTRACTION')),
    previous_audit_id UUID REFERENCES source_review_decision_audit(id) ON DELETE RESTRICT,
    inherited_audit_id UUID REFERENCES source_review_decision_audit(id) ON DELETE RESTRICT,
    PRIMARY KEY (current_document_id, previous_candidate_id)
);
