CREATE TABLE confirmed_case_snapshot (
    id UUID PRIMARY KEY,
    consultation_id UUID NOT NULL,
    case_input_revision BIGINT NOT NULL,
    follow_up_answer_revision BIGINT NOT NULL,
    facts_json TEXT NOT NULL,
    missing_facts_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_confirmed_case_snapshot_consultation
        FOREIGN KEY (consultation_id)
        REFERENCES consultation (id)
        ON DELETE CASCADE,

    CONSTRAINT ck_confirmed_case_snapshot_case_revision
        CHECK (case_input_revision >= 0),

    CONSTRAINT ck_confirmed_case_snapshot_followup_revision
        CHECK (follow_up_answer_revision >= 0),

    CONSTRAINT ck_confirmed_case_snapshot_facts_json
        CHECK (jsonb_typeof(facts_json::jsonb) = 'array'),

    CONSTRAINT ck_confirmed_case_snapshot_missing_json
        CHECK (jsonb_typeof(missing_facts_json::jsonb) = 'array'),

    CONSTRAINT uk_confirmed_case_snapshot_revision
        UNIQUE (consultation_id, case_input_revision, follow_up_answer_revision)
);

CREATE INDEX idx_confirmed_case_snapshot_consultation
    ON confirmed_case_snapshot (consultation_id, case_input_revision DESC, follow_up_answer_revision DESC);
