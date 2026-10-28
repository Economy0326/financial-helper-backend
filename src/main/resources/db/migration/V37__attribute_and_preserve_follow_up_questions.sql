ALTER TABLE follow_up_question
    ADD COLUMN question_scope VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNATTRIBUTED',
    ADD COLUMN procedure_version_id UUID,
    ADD COLUMN fact_contract_id VARCHAR(128),
    ADD COLUMN superseded_at TIMESTAMPTZ;

ALTER TABLE follow_up_question
    ADD CONSTRAINT fk_follow_up_question_procedure_version
        FOREIGN KEY (procedure_version_id) REFERENCES procedure_version (id),
    ADD CONSTRAINT ck_follow_up_question_scope
        CHECK (
            (question_scope = 'LEGACY_UNATTRIBUTED'
                AND procedure_version_id IS NULL AND fact_contract_id IS NULL)
            OR (question_scope = 'VERSION_BOUND'
                AND procedure_version_id IS NOT NULL AND fact_contract_id IS NULL)
            OR (question_scope IN ('SCENARIO_ROUTING', 'COMMON_DATE')
                AND procedure_version_id IS NULL
                AND fact_contract_id IS NOT NULL
                AND length(trim(fact_contract_id)) > 0)
        );

CREATE INDEX idx_follow_up_question_active
    ON follow_up_question (consultation_id, case_input_revision, sequence_no)
    WHERE superseded_at IS NULL;
