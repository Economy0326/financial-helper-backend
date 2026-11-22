CREATE TABLE retrieval_generation_claim_scope (
    retrieval_generation_id UUID NOT NULL,
    source_chunk_id UUID NOT NULL,
    scope_candidate_id UUID NOT NULL REFERENCES source_claim_scope_candidate(id) ON DELETE RESTRICT,
    scope_review_id UUID NOT NULL REFERENCES source_claim_scope_review(id) ON DELETE RESTRICT,
    approval_audit_id UUID NOT NULL REFERENCES source_review_decision_audit(id) ON DELETE RESTRICT,
    scope_text TEXT NOT NULL,
    evidence_sha256 VARCHAR(64) NOT NULL,
    PRIMARY KEY (retrieval_generation_id, source_chunk_id, scope_candidate_id),
    FOREIGN KEY (retrieval_generation_id, source_chunk_id)
        REFERENCES retrieval_generation_claim_review(retrieval_generation_id, source_chunk_id) ON DELETE RESTRICT
);
