-- Grants are provisioned out of band by the owner of a dedicated database.
-- No application endpoint can grant itself reviewer access.
CREATE TABLE source_reviewer_grant (
    account_id UUID PRIMARY KEY REFERENCES account(id) ON DELETE RESTRICT,
    granted_by VARCHAR(150) NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,
    revoked_by VARCHAR(150),
    revocation_reason TEXT,
    CONSTRAINT ck_source_reviewer_grant_name CHECK (length(btrim(granted_by)) > 0),
    CONSTRAINT ck_source_reviewer_grant_revocation CHECK
        ((revoked_at IS NULL AND revoked_by IS NULL AND revocation_reason IS NULL) OR
         (revoked_at IS NOT NULL AND length(btrim(revoked_by)) > 0
          AND length(btrim(revocation_reason)) > 0))
);

CREATE TABLE source_review_decision_audit (
    id UUID PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES source_applicability_review(id) ON DELETE RESTRICT,
    source_document_id UUID NOT NULL REFERENCES source_document(id) ON DELETE RESTRICT,
    reviewer_account_id UUID NOT NULL REFERENCES account(id) ON DELETE RESTRICT,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED', 'REVOKED')),
    decision_reason VARCHAR(1000) NOT NULL CHECK (length(btrim(decision_reason)) > 0),
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_raw_sha256 VARCHAR(64) NOT NULL,
    CONSTRAINT fk_source_review_decision_document_hash
        FOREIGN KEY (source_document_id, reviewed_raw_sha256)
        REFERENCES source_document(id, raw_sha256) ON DELETE RESTRICT
);
CREATE INDEX idx_source_review_decision_audit_review ON source_review_decision_audit(review_id, decided_at);

ALTER TABLE source_document ADD COLUMN metadata_source_url TEXT;

ALTER TABLE source_applicability_review ADD COLUMN question_number SMALLINT;
ALTER TABLE source_applicability_review ADD CONSTRAINT ck_applicability_question_number
    CHECK (question_number IS NULL OR question_number BETWEEN 1 AND 99);
CREATE UNIQUE INDEX uk_deposit_review_current_question
    ON source_applicability_review(source_document_id, question_number)
    WHERE review_target_kind = 'TOPIC' AND financial_topic = 'DEPOSITS_ACCOUNTS'
      AND question_number IS NOT NULL AND review_status IN ('PENDING', 'APPROVED');

ALTER TABLE source_applicability_review DROP CONSTRAINT ck_applicability_status;
ALTER TABLE source_applicability_review ADD CONSTRAINT ck_applicability_status
    CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED'));
ALTER TABLE source_applicability_review DROP CONSTRAINT ck_applicability_reviewed;
ALTER TABLE source_applicability_review ADD CONSTRAINT ck_applicability_reviewed CHECK (
    (review_status = 'PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL
        AND revoked_at IS NULL AND revoked_by IS NULL AND revocation_reason IS NULL)
    OR (review_status = 'APPROVED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
        AND revoked_at IS NULL AND revoked_by IS NULL AND revocation_reason IS NULL
        AND conditions_reviewed AND exceptions_reviewed)
    OR (review_status = 'REJECTED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
        AND revoked_at IS NULL AND revoked_by IS NULL AND revocation_reason IS NULL)
    OR (review_status = 'REVOKED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
        AND revoked_at IS NOT NULL AND revoked_by IS NOT NULL
        AND length(btrim(revocation_reason)) > 0)
);
