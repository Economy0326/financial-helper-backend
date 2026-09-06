CREATE TABLE analysis_job (
    id UUID PRIMARY KEY,

    consultation_id UUID NOT NULL,

    case_input_revision BIGINT NOT NULL,
    follow_up_answer_revision BIGINT NOT NULL,

    status VARCHAR(32) NOT NULL,

    attempt_count INTEGER NOT NULL,

    model VARCHAR(100) NOT NULL,

    result_json TEXT,

    failure_code VARCHAR(64),

    created_at TIMESTAMPTZ NOT NULL,
    queued_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,

    CONSTRAINT fk_analysis_job_consultation
        FOREIGN KEY (consultation_id)
        REFERENCES consultation (id)
        ON DELETE CASCADE,

    CONSTRAINT ck_analysis_job_case_revision_non_negative
        CHECK (case_input_revision >= 0),

    CONSTRAINT ck_analysis_job_answer_revision_non_negative
        CHECK (follow_up_answer_revision >= 0),

    CONSTRAINT ck_analysis_job_attempt_positive
        CHECK (attempt_count > 0),

    CONSTRAINT uk_analysis_job_revision
        UNIQUE (
            consultation_id,
            case_input_revision,
            follow_up_answer_revision
        )
);

CREATE INDEX idx_analysis_job_consultation_id
    ON analysis_job (consultation_id);

CREATE INDEX idx_analysis_job_status
    ON analysis_job (status);