CREATE TABLE retrieval_search_context (
    id UUID PRIMARY KEY,
    consultation_id UUID,
    account_id UUID,
    case_input_revision BIGINT NOT NULL,
    follow_up_answer_revision BIGINT NOT NULL,
    retrieval_generation_id UUID,
    response_json TEXT NOT NULL,
    candidates_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_search_context_consultation FOREIGN KEY (consultation_id)
        REFERENCES consultation (id),
    CONSTRAINT fk_search_context_account FOREIGN KEY (account_id)
        REFERENCES account (id),
    CONSTRAINT fk_search_context_generation FOREIGN KEY (retrieval_generation_id)
        REFERENCES retrieval_generation (id)
);

CREATE INDEX idx_search_context_consultation ON retrieval_search_context (consultation_id);
CREATE INDEX idx_search_context_account ON retrieval_search_context (account_id);
CREATE INDEX idx_search_context_expiry ON retrieval_search_context (expires_at);
