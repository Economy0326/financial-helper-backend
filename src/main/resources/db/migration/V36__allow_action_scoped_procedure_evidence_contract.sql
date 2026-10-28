ALTER TABLE procedure_version
    DROP CONSTRAINT ck_procedure_version_evidence_references_json;

ALTER TABLE procedure_version
    ADD CONSTRAINT ck_procedure_version_evidence_references_json
    CHECK ((
        jsonb_typeof(evidence_references_json::jsonb) = 'array'
        OR (
            jsonb_typeof(evidence_references_json::jsonb) = 'object'
            AND evidence_references_json::jsonb ->> 'contract' = 'ACTION_SCOPED'
            AND jsonb_typeof(evidence_references_json::jsonb -> 'references') = 'array'
        )
    ) IS TRUE);
