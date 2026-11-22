-- Scoped index text and corpus hashes cannot be safely rewritten in place.
-- A deployment containing such generations must first build a compatible generation.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM retrieval_generation_claim_scope) THEN
        RAISE EXCEPTION 'Claim scope generation bindings exist; rebuild compatible generations before migration';
    END IF;
END $$;

DROP TABLE retrieval_generation_claim_scope;
DROP TABLE source_claim_scope_review;
DROP TABLE source_claim_scope_candidate;
ALTER TABLE source_document_claim DROP CONSTRAINT uk_scope_claim_document;
