-- Registration is a collection candidate, not a review or READY decision.
-- The police campaign is already registered by V10 and is deliberately reused.
INSERT INTO official_source_domain (domain, organization_name)
VALUES ('law.go.kr', '국가법령정보센터')
ON CONFLICT (domain) DO NOTHING;

INSERT INTO source_registry
    (id, source_key, organization_name, official_domain, canonical_url, acquisition_type)
VALUES
    ('47000000-0000-0000-0000-000000000001', 'fsc-card-use-qa', '금융위원회',
     'fsc.go.kr', 'https://www.fsc.go.kr/po020201/27315', 'HTML'),
    ('47000000-0000-0000-0000-000000000002', 'fsc-early-repayment-qa', '금융위원회',
     'fsc.go.kr', 'https://www.fsc.go.kr/po020201/83840', 'HTML'),
    ('47000000-0000-0000-0000-000000000003', 'fsc-silsong24-guide', '금융위원회',
     'fsc.go.kr', 'https://www.fsc.go.kr/po010103/87818', 'HTML'),
    ('47000000-0000-0000-0000-000000000004', 'law-financial-consumer-protection-19', '국가법령정보센터',
     'law.go.kr', 'https://www.law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1031274847', 'HTML'),
    ('47000000-0000-0000-0000-000000000005', 'fsc-consumer-complaints', '금융위원회',
     'fsc.go.kr', 'https://www.fsc.go.kr/pa010000', 'HTML');
