-- Existing V38 scenario reviews remain SCENARIO reviews. A topic review has no
-- fabricated incident scenario; its applicability window is independently reviewed.
ALTER TABLE source_applicability_review
    ADD COLUMN review_target_kind VARCHAR(16) NOT NULL DEFAULT 'SCENARIO',
    ADD COLUMN financial_topic VARCHAR(80);

ALTER TABLE source_applicability_review
    ALTER COLUMN scenario DROP NOT NULL;

ALTER TABLE source_applicability_review
    ADD CONSTRAINT ck_applicability_review_target CHECK (
        (review_target_kind = 'SCENARIO' AND scenario IS NOT NULL AND financial_topic IS NULL)
        OR (review_target_kind = 'TOPIC' AND scenario IS NULL AND event_type IS NULL
            AND financial_topic IN ('CARD_PAYMENTS', 'DEPOSITS_ACCOUNTS', 'LOANS_CREDIT',
                'INSURANCE', 'INVESTMENTS_SECURITIES', 'FRAUD_SECURITY',
                'CONSUMER_RIGHTS_DISPUTES'))
    );

CREATE INDEX idx_applicability_topic_lookup ON source_applicability_review
    (financial_topic, review_status, applicability_start_date, applicability_end_date)
    WHERE review_target_kind = 'TOPIC';
