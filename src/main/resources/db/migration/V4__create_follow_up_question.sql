CREATE TABLE follow_up_question (
    id UUID PRIMARY KEY,

    consultation_id UUID NOT NULL,

    case_input_revision BIGINT NOT NULL,

    sequence_no INTEGER NOT NULL,

    question_text VARCHAR(300) NOT NULL,

    description VARCHAR(500) NOT NULL,

    options_json TEXT NOT NULL,

    model VARCHAR(100) NOT NULL,

    answer_value VARCHAR(64),

    answer_label VARCHAR(200),

    generated_at TIMESTAMPTZ NOT NULL,

    answered_at TIMESTAMPTZ,

    CONSTRAINT fk_follow_up_question_consultation
        FOREIGN KEY (consultation_id)
        REFERENCES consultation (id)
        ON DELETE CASCADE,

    CONSTRAINT ck_follow_up_question_revision_non_negative
        CHECK (case_input_revision >= 0),

    CONSTRAINT ck_follow_up_question_sequence_positive
        CHECK (sequence_no > 0),

    CONSTRAINT uk_follow_up_question_revision_sequence
        UNIQUE (
            consultation_id,
            case_input_revision,
            sequence_no
        )
);

CREATE INDEX idx_follow_up_question_consultation_revision
    ON follow_up_question (
        consultation_id,
        case_input_revision
    );