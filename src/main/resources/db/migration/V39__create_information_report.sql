CREATE TABLE information_report (
    id UUID PRIMARY KEY,
    consultation_id UUID NOT NULL REFERENCES consultation(id) ON DELETE CASCADE,
    case_input_revision BIGINT NOT NULL CHECK (case_input_revision >= 0),
    follow_up_answer_revision BIGINT NOT NULL CHECK (follow_up_answer_revision >= 0),
    retrieval_generation_id UUID,
    candidates_json TEXT NOT NULL,
    snapshot_json TEXT NOT NULL,
    selected_evidence_json TEXT NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_information_report_consultation_generated
    ON information_report(consultation_id, generated_at DESC);
