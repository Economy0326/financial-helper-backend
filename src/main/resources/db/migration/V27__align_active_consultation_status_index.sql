DROP INDEX IF EXISTS uk_consultation_one_active_per_account;

CREATE UNIQUE INDEX uk_consultation_one_active_per_account
    ON consultation (account_id)
    WHERE account_id IS NOT NULL
      AND status IN ('IN_PROGRESS', 'ANALYZING', 'NEEDS_MORE_INFO', 'FAILED');
