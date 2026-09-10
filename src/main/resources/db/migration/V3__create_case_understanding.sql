CREATE TABLE case_understanding (
    id UUID PRIMARY KEY,

    consultation_id UUID NOT NULL,

    -- 어떤 사용자 원본 입력 Revision을 기반으로 생성됐는가
    case_input_revision BIGINT NOT NULL,

    -- 어떤 모델로 생성했는지 기록
    model VARCHAR(100) NOT NULL,

    -- 검증을 통과한 Structured AI Result
    result_json TEXT NOT NULL,

    generated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_case_understanding_consultation
        FOREIGN KEY (consultation_id)
        REFERENCES consultation (id)
        ON DELETE CASCADE,

    CONSTRAINT ck_case_understanding_revision_non_negative
        CHECK (case_input_revision >= 0),

    CONSTRAINT uk_case_understanding_consultation_revision
        UNIQUE (
            consultation_id,
            case_input_revision
        )
);

CREATE INDEX idx_case_understanding_consultation_id
    ON case_understanding (consultation_id);