-- A prepared candidate can have at most one external build owner. No production
-- backfill or modification of existing generations is performed.
CREATE TABLE mixed_generation_build_attempt (
    generation_id UUID PRIMARY KEY REFERENCES retrieval_generation(id),
    attempt_id UUID NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'BUILT', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
