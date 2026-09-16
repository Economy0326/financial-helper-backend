-- Emergency sources reviewed against official originals on 2026-09-16.
-- This migration only records provenance and narrows one unsupported action;
-- the deterministic runtime contract is unchanged.

WITH expanded AS (
    SELECT
        s.id,
        jsonb_agg(
            CASE src->>'id'
                WHEN 'fsc-2026-alert' THEN src || jsonb_build_object(
                    'canonicalUrl', 'https://www.fsc.go.kr/no010101/86271?curPage=2',
                    'officialDomain', 'fsc.go.kr',
                    'checkedDate', '2026-09-16',
                    'reviewStatus', 'SUPPORTED'
                )
                WHEN 'fsc-card' THEN src || jsonb_build_object(
                    'canonicalUrl', 'https://fsc.go.kr/no040101?cnId=2361&curPage=145&pastPage=145',
                    'officialDomain', 'fsc.go.kr',
                    'checkedDate', '2026-09-16',
                    'reviewStatus', 'SUPPORTED'
                )
                WHEN 'fsc-personal-info' THEN src || jsonb_build_object(
                    'canonicalUrl', 'https://www.fsc.go.kr/no040104?cnId=1527',
                    'officialDomain', 'fsc.go.kr',
                    'checkedDate', '2026-09-16',
                    'reviewStatus', 'SUPPORTED'
                )
                WHEN 'police-campaign' THEN src || jsonb_build_object(
                    'canonicalUrl', 'https://www.counterscam112.go.kr/campaign/index.html',
                    'officialDomain', 'counterscam112.go.kr',
                    'checkedDate', '2026-09-16',
                    'reviewStatus', 'SUPPORTED'
                )
                WHEN 'police-response' THEN src || jsonb_build_object(
                    'canonicalUrl', 'https://www.counterscam112.go.kr/board/CONTENT_000000000009.do',
                    'officialDomain', 'counterscam112.go.kr',
                    'checkedDate', '2026-09-16',
                    'reviewStatus', 'SUPPORTED'
                )
                WHEN 'police-reporting' THEN src || jsonb_build_object(
                    'canonicalUrl', 'https://www.counterscam112.go.kr/board/CONTENT_000000000010.do',
                    'officialDomain', 'counterscam112.go.kr',
                    'checkedDate', '2026-09-16',
                    'reviewStatus', 'SUPPORTED'
                )
                WHEN 'kisa-118' THEN src || jsonb_build_object(
                    'canonicalUrl', 'https://www.kisa.or.kr/118',
                    'officialDomain', 'kisa.or.kr',
                    'checkedDate', '2026-09-16',
                    'reviewStatus', 'SUPPORTED'
                )
                ELSE src
            END
            ORDER BY ord
        ) AS sources
    FROM emergency_scenario s
    CROSS JOIN LATERAL jsonb_array_elements(s.payload_json::jsonb->'sources') WITH ORDINALITY AS x(src, ord)
    GROUP BY s.id
)
UPDATE emergency_scenario s
SET payload_json = jsonb_set(s.payload_json::jsonb, '{sources}', expanded.sources)::text,
    source_checked_at = DATE '2026-09-16'
FROM expanded
WHERE s.id = expanded.id;

-- The provisional wording implied an unverified device-isolation step. Keep the
-- safe official action (do not continue using suspicious links/apps and contact
-- official channels) without asserting an unsupported airplane-mode procedure.
WITH expanded AS (
    SELECT
        s.id,
        jsonb_agg(
            CASE action->>'id'
                WHEN 'app-do-2' THEN jsonb_build_object(
                    'id', 'app-do-2',
                    'title', '안전한 방법으로 공식 채널에 신고하세요.',
                    'description', '의심 링크나 앱을 더 이용하지 말고, 피해가 의심되면 안전한 방법으로 112 또는 이용 중인 금융회사에 연락하세요.',
                    'sourceIds', jsonb_build_array('fsc-2026-alert', 'police-campaign')
                )
                ELSE action
            END
            ORDER BY ord
        ) AS actions
    FROM emergency_scenario s
    CROSS JOIN LATERAL jsonb_array_elements(s.payload_json::jsonb->'actionsToDo') WITH ORDINALITY AS x(action, ord)
    WHERE s.emergency_type = 'SUSPICIOUS_APP'
    GROUP BY s.id
)
UPDATE emergency_scenario s
SET payload_json = jsonb_set(s.payload_json::jsonb, '{actionsToDo}', expanded.actions)::text
FROM expanded
WHERE s.id = expanded.id;
