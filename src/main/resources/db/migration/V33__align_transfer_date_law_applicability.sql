-- B records the event date under transactionDate.  Keep that fact required
-- for the same historical-law fail-closed boundary used by A.
UPDATE procedure_version
SET required_facts_json = (
        SELECT jsonb_agg(
                   CASE
                       WHEN node->>'key' = 'transactionDate'
                           THEN jsonb_set(node, '{requiredForDecision}', 'true'::jsonb)
                       ELSE node
                   END
                   ORDER BY ord
               )::text
        FROM jsonb_array_elements(required_facts_json::jsonb)
                 WITH ORDINALITY AS facts(node, ord)
    ),
    review_notes = replace(
        review_notes,
        'Incident date is required before reviewed law version evidence is attached.',
        'Event date is required before reviewed law version evidence is attached.'
    )
WHERE scenario = 'UNAUTHORIZED_ACCOUNT_TRANSFER'
  AND status = 'APPROVED';
