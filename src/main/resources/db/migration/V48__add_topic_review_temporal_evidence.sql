-- Existing scenario and deposit reviews retain their explicit date windows.
-- Topic claims can instead carry a bounded, independently verified temporal policy.
ALTER TABLE source_applicability_review
    ALTER COLUMN applicability_start_date DROP NOT NULL,
    ADD COLUMN temporal_policy VARCHAR(32) NOT NULL DEFAULT 'EXPLICIT_EFFECTIVE',
    ADD COLUMN temporal_verified_on DATE,
    ADD COLUMN temporal_valid_until DATE;

ALTER TABLE source_applicability_review ADD CONSTRAINT ck_topic_temporal_evidence CHECK (
    (review_target_kind = 'SCENARIO' AND temporal_policy = 'EXPLICIT_EFFECTIVE'
        AND applicability_start_date IS NOT NULL
        AND temporal_verified_on IS NULL AND temporal_valid_until IS NULL)
    OR (review_target_kind = 'TOPIC' AND (
        (temporal_policy = 'EXPLICIT_EFFECTIVE' AND applicability_start_date IS NOT NULL)
        OR (temporal_policy = 'VERSION_EFFECTIVE' AND applicability_start_date IS NOT NULL
            AND (applicability_end_date IS NOT NULL OR
                 (temporal_verified_on IS NOT NULL AND temporal_valid_until IS NOT NULL
                  AND temporal_valid_until BETWEEN temporal_verified_on
                      AND temporal_verified_on + 365)))
        OR (temporal_policy IN ('CONTRACT_DATE', 'HISTORICAL_FACT')
            AND applicability_start_date IS NOT NULL AND applicability_end_date IS NOT NULL)
        OR (temporal_policy IN ('CURRENT_GUIDANCE', 'DYNAMIC_CHECK')
            AND applicability_start_date IS NULL
            AND temporal_verified_on IS NOT NULL AND temporal_valid_until IS NOT NULL
            AND temporal_valid_until >= temporal_verified_on
            AND temporal_valid_until <= temporal_verified_on +
                CASE WHEN temporal_policy = 'DYNAMIC_CHECK' THEN 30 ELSE 365 END
            AND applicability_end_date IS NULL)
        OR (temporal_policy = 'HOLD' AND review_status IN ('PENDING', 'REJECTED', 'REVOKED')
            AND applicability_start_date IS NULL AND applicability_end_date IS NULL
            AND temporal_verified_on IS NULL AND temporal_valid_until IS NULL)
    ))
);
