-- A and B have reviewed legal evidence whose historical version is selected
-- by incident date.  Keeping the date decision fact required prevents a
-- current law version from being silently applied to an unknown historical
-- incident.  UNKNOWN remains a valid answer and produces partial guidance.
UPDATE procedure_version
SET required_facts_json = (
        SELECT jsonb_agg(
                   CASE
                       WHEN node->>'key' = 'incidentDate'
                           THEN jsonb_set(node, '{requiredForDecision}', 'true'::jsonb)
                       ELSE node
                   END
                   ORDER BY ord
               )::text
        FROM jsonb_array_elements(required_facts_json::jsonb)
                 WITH ORDINALITY AS facts(node, ord)
    ),
    review_notes = review_notes || ' Incident date is required before reviewed law version evidence is attached.'
WHERE scenario IN (
    'VOICE_PHISHING_SUSPICIOUS_TRANSFER',
    'UNAUTHORIZED_ACCOUNT_TRANSFER'
)
  AND status = 'APPROVED';
