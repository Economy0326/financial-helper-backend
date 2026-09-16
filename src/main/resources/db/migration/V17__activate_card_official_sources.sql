-- CARD corpus registry entries.  Registry membership is a catalog decision;
-- it does not grant document or chunk approval.
ALTER TABLE source_registry
    DROP CONSTRAINT ck_source_registry_acquisition_type;

ALTER TABLE source_registry
    ADD CONSTRAINT ck_source_registry_acquisition_type
        CHECK (acquisition_type IN ('HTML', 'PDF', 'HWP'));

INSERT INTO official_source_domain (
    domain,
    organization_name
)
VALUES
    ('card.kbcard.com', '㈜KB국민카드'),
    ('img2.kbcard.com', '㈜KB국민카드'),
    ('mapps.kbcard.com', '㈜KB국민카드'),
    ('m.crefia.or.kr', '여신금융협회')
ON CONFLICT (domain, organization_name) DO NOTHING;

INSERT INTO source_registry (
    id,
    source_key,
    organization_name,
    official_domain,
    canonical_url,
    acquisition_type,
    enabled
)
VALUES
    (
        '20000000-0000-0000-0000-000000000101',
        'kb-personal-card-terms-260402',
        '㈜KB국민카드',
        'img2.kbcard.com',
        'https://img2.kbcard.com/obj/contents/download/standardagreement_260402.pdf',
        'PDF',
        TRUE
    ),
    (
        '20000000-0000-0000-0000-000000000102',
        'kb-unauthorized-compensation-form-260209',
        '㈜KB국민카드',
        'img2.kbcard.com',
        'https://img2.kbcard.com/obj/contents/download/20260209_unauthorize.pdf',
        'PDF',
        TRUE
    ),
    (
        '20000000-0000-0000-0000-000000000103',
        'crefia-card-loss-compensation-guideline-221021',
        '여신금융협회',
        'm.crefia.or.kr',
        'https://m.crefia.or.kr/mobile/infocenter/regulation/selfRegulation.xx',
        'HWP',
        FALSE
    ),
    (
        '20000000-0000-0000-0000-000000000104',
        'kb-card-compensation-process',
        '㈜KB국민카드',
        'mapps.kbcard.com',
        'https://mapps.kbcard.com/SVC/DVIEW/HSGMCXCRSCSC0004',
        'HTML',
        TRUE
    ),
    (
        '20000000-0000-0000-0000-000000000105',
        'kb-card-loss-report-ars',
        '㈜KB국민카드',
        'mapps.kbcard.com',
        'https://mapps.kbcard.com/SVC/DVIEW/HSGMCXCRSCSC0030',
        'HTML',
        TRUE
    ),
    (
        '20000000-0000-0000-0000-000000000106',
        'kb-card-terms-amendment-260402',
        '㈜KB국민카드',
        'card.kbcard.com',
        'https://card.kbcard.com/CMN/DVIEW/HSEMCXCRSCTC0001?ARTICLE_SERIAL=12107&ROUTE_TYPE=VIEW',
        'HTML',
        TRUE
    )
ON CONFLICT (source_key) DO NOTHING;
