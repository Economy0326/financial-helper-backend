CREATE TABLE document_claim_faq_evidence (
    faq_id UUID NOT NULL REFERENCES document_claim_faq(id) ON DELETE CASCADE,
    candidate_id VARCHAR(64) NOT NULL REFERENCES source_document_claim(candidate_id) ON DELETE RESTRICT,
    position INTEGER NOT NULL CHECK (position >= 0),
    PRIMARY KEY (faq_id, candidate_id),
    UNIQUE (faq_id, position)
);
INSERT INTO document_claim_faq_evidence(faq_id,candidate_id,position)
SELECT id,candidate_id,0 FROM document_claim_faq;
