CREATE TABLE analysis_evidence_snapshot (
    id UUID PRIMARY KEY,
    consultation_id UUID NOT NULL,
    analysis_job_id UUID NOT NULL,
    procedure_version_id UUID NOT NULL,
    financial_action_plan_id UUID NOT NULL,
    retrieval_generation_id UUID NOT NULL,
    case_input_revision BIGINT NOT NULL,
    follow_up_answer_revision BIGINT NOT NULL,
    snapshot_revision INTEGER NOT NULL,
    snapshot_status VARCHAR(32) NOT NULL,
    snapshot_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_analysis_evidence_snapshot_consultation
        FOREIGN KEY (consultation_id) REFERENCES consultation (id) ON DELETE CASCADE,
    CONSTRAINT fk_analysis_evidence_snapshot_analysis_job
        FOREIGN KEY (analysis_job_id) REFERENCES analysis_job (id) ON DELETE CASCADE,
    CONSTRAINT fk_analysis_evidence_snapshot_procedure
        FOREIGN KEY (procedure_version_id) REFERENCES procedure_version (id),
    CONSTRAINT fk_analysis_evidence_snapshot_plan
        FOREIGN KEY (financial_action_plan_id) REFERENCES financial_action_plan (id),
    CONSTRAINT fk_analysis_evidence_snapshot_generation
        FOREIGN KEY (retrieval_generation_id) REFERENCES retrieval_generation (id),
    CONSTRAINT uk_analysis_evidence_snapshot_job_revision
        UNIQUE (analysis_job_id, case_input_revision, follow_up_answer_revision),
    CONSTRAINT ck_analysis_evidence_snapshot_status
        CHECK (snapshot_status IN ('READY', 'INVALID')),
    CONSTRAINT ck_analysis_evidence_snapshot_revision
        CHECK (snapshot_revision > 0)
);

CREATE INDEX ix_analysis_evidence_snapshot_consultation_revision
    ON analysis_evidence_snapshot (
        consultation_id, case_input_revision, follow_up_answer_revision
    );
