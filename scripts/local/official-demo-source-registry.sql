-- Candidate registration in the isolated official demo database only.
-- This does not create a SourceDocument, Review approval, or READY generation.
INSERT INTO source_registry
    (id, source_key, organization_name, official_domain, canonical_url,
     acquisition_type, enabled, created_at, updated_at)
VALUES
    (gen_random_uuid(), 'fsc-deposit-protection-qa-pdf', '금융위원회', 'fsc.go.kr',
     'https://www.fsc.go.kr/comm/getFile?srvcId=BBSTY1&upperNo=84975&fileTy=ATTACH&fileNo=1',
     'PDF', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (source_key) DO NOTHING;
