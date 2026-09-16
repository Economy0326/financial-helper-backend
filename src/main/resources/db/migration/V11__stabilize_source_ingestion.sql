ALTER TABLE source_registry
ADD COLUMN content_selector VARCHAR(500);

-- 기존 police-response URL은 현재 404이므로
-- 직접 획득 가능한 현재 공식 통합대응단 소개 URL로 교체
UPDATE source_registry
SET
    canonical_url =
        'https://www.counterscam112.go.kr/board/CONTENT_000000000015.do',
    updated_at = CURRENT_TIMESTAMP
WHERE source_key = 'police-response';

-- 금융위원회 카드뉴스는 전체 페이지가 아니라
-- 현재 카드뉴스 제목 + 실제 본문 영역만 추출
UPDATE source_registry
SET
    content_selector = '.photo-list-body',
    updated_at = CURRENT_TIMESTAMP
WHERE source_key IN (
    'fsc-card',
    'fsc-personal-info'
);