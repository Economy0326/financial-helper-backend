-- Preserve the exact reviewer decision used when a generation's input was frozen.
-- Existing generations are deliberately not backfilled: their original decision cannot be proven.
ALTER TABLE source_applicability_review ADD COLUMN current_claim_approval_id UUID
    REFERENCES source_review_decision_audit(id) ON DELETE RESTRICT;

CREATE TABLE retrieval_generation_claim_review (
    retrieval_generation_id UUID NOT NULL REFERENCES retrieval_generation(id) ON DELETE RESTRICT,
    source_chunk_id UUID NOT NULL REFERENCES source_chunk(id) ON DELETE RESTRICT,
    review_id UUID NOT NULL REFERENCES source_applicability_review(id) ON DELETE RESTRICT,
    approval_audit_id UUID NOT NULL REFERENCES source_review_decision_audit(id) ON DELETE RESTRICT,
    PRIMARY KEY (retrieval_generation_id, source_chunk_id)
);
