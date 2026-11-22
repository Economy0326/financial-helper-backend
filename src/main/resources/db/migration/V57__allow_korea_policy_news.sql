-- Public policy-news platform; document rights and reviewer decisions remain separate.
INSERT INTO official_source_domain(domain, organization_name)
VALUES ('korea.kr', '대한민국 정책브리핑')
ON CONFLICT (domain) DO NOTHING;
