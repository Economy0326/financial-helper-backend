CREATE TABLE consultation_summary (
    id UUID PRIMARY KEY,

    consultation_id UUID NOT NULL,

    case_input_revision BIGINT NOT NULL,
    follow_up_answer_revision BIGINT NOT NULL,

    model VARCHAR(100) NOT NULL,

    result_json TEXT NOT NULL,

    generated_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,

    CONSTRAINT fk_consultation_summary_consultation
        FOREIGN KEY (consultation_id)
        REFERENCES consultation (id)
        ON DELETE CASCADE,

    CONSTRAINT ck_consultation_summary_case_input_revision_non_negative
        CHECK (case_input_revision >= 0),

    CONSTRAINT ck_consultation_summary_follow_up_answer_revision_non_negative
        CHECK (follow_up_answer_revision >= 0),

    CONSTRAINT uk_consultation_summary_revision
        UNIQUE (
            consultation_id,
            case_input_revision,
            follow_up_answer_revision
        )
);

CREATE INDEX idx_consultation_summary_consultation_id
    ON consultation_summary (consultation_id);