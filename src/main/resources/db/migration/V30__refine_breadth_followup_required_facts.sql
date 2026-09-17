-- Refine the breadth follow-up contract after the initial catalog migration.
-- This keeps scenario definitions versioned and makes the first useful fact
-- deterministic without changing any previously applied migration.

WITH updated AS (
    SELECT
        id,
        jsonb_agg(
            CASE
                WHEN node->>'key' IN (
                    'transferCompleted', 'userInitiatedTransfer',
                    'reportedToFinancialInstitution', 'policeReported',
                    'transactionType', 'reported', 'accessCredentialExposed',
                    'suspiciousLinkClicked', 'maliciousAppInstalled',
                    'remoteControlUsed', 'personalInfoExposed',
                    'authenticationInfoExposed', 'moneyMoved'
                ) THEN jsonb_set(node, '{requiredForDecision}', 'true'::jsonb)
                ELSE node
            END
            ORDER BY ord
        )::text AS required_facts_json
    FROM procedure_version
    CROSS JOIN LATERAL jsonb_array_elements(required_facts_json::jsonb)
        WITH ORDINALITY AS facts(node, ord)
    WHERE scenario IN (
        'VOICE_PHISHING_SUSPICIOUS_TRANSFER',
        'UNAUTHORIZED_ACCOUNT_TRANSFER',
        'PERSONAL_INFO_SMISHING_MALICIOUS_APP'
    )
    GROUP BY id
)
UPDATE procedure_version procedure
SET required_facts_json = updated.required_facts_json,
    updated_at = CURRENT_TIMESTAMP
FROM updated
WHERE procedure.id = updated.id;
