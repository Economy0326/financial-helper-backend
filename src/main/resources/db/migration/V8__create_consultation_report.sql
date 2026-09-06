CREATE TABLE consultation_report (
    id UUID PRIMARY KEY,

    consultation_id UUID NOT NULL,

    analysis_job_id UUID NOT NULL,

    case_input_revision BIGINT NOT NULL,
    follow_up_answer_revision BIGINT NOT NULL,

    model VARCHAR(100) NOT NULL,

    result_json TEXT NOT NULL,

    generated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_consultation_report_consultation
        FOREIGN KEY (consultation_id)
        REFERENCES consultation (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_consultation_report_analysis_job
        FOREIGN KEY (analysis_job_id)
        REFERENCES analysis_job (id)
        ON DELETE CASCADE,

    CONSTRAINT uk_consultation_report_revision
        UNIQUE (
            consultation_id,
            case_input_revision,
            follow_up_answer_revision
        ),

    CONSTRAINT uk_consultation_report_analysis_job
        UNIQUE (analysis_job_id),

    CONSTRAINT ck_consultation_report_case_revision
        CHECK (case_input_revision >= 0),

    CONSTRAINT ck_consultation_report_answer_revision
        CHECK (follow_up_answer_revision >= 0)
);

CREATE INDEX idx_consultation_report_consultation
    ON consultation_report (consultation_id);