-- 직접 접근해도 되는 공식 도메인 목록
CREATE TABLE official_source_domain (
    domain VARCHAR(255) PRIMARY KEY,
    organization_name VARCHAR(150) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_official_source_domain_organization
        UNIQUE (domain, organization_name)
);

-- 공식 문서 수집 방법
CREATE TABLE source_registry (
    id UUID PRIMARY KEY,
    source_key VARCHAR(64) NOT NULL,
    organization_name VARCHAR(150) NOT NULL,
    official_domain VARCHAR(255) NOT NULL,

    -- canonical_url => 기준으로 삼는 공식 원문 URL
    canonical_url TEXT NOT NULL,

    -- HTML/PDF/API 같은 수집 방식, 현재는 HTML만
    acquisition_type VARCHAR(32) NOT NULL,

    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_source_registry_source_key
        UNIQUE (source_key),

    CONSTRAINT uk_source_registry_canonical_url
        UNIQUE (canonical_url),

    -- allowlist에 없는 source 금지
    CONSTRAINT fk_source_registry_official_domain
        FOREIGN KEY (
            official_domain,
            organization_name
        )
        REFERENCES official_source_domain (
            domain,
            organization_name
        ),

    -- HTML만 일단 가능
    CONSTRAINT ck_source_registry_acquisition_type
        CHECK (
            acquisition_type IN ('HTML')
        )
);

-- 공식 사이트에서 실제로 가져온 한 시점의 문서 snapshot
CREATE TABLE source_document (
    id UUID PRIMARY KEY,

    -- 어떤 source에서 가져온 문서인지
    source_registry_id UUID NOT NULL,

    document_version INTEGER NOT NULL,

    status VARCHAR(32) NOT NULL,

    organization_name VARCHAR(150) NOT NULL,

    official_domain VARCHAR(255) NOT NULL,

    canonical_url TEXT NOT NULL,

    -- redirect까지 끝난 실제 최종 URL
    resolved_url TEXT NOT NULL,

    title TEXT NOT NULL,

    published_at DATE,

    -- Backend가 해당 version을 실제 수집한 시각
    retrieved_at TIMESTAMPTZ NOT NULL,

    last_checked_at TIMESTAMPTZ NOT NULL,

    content_type VARCHAR(255),

    -- 서버 제공시 변경 추적 보조 정보
    -- 서버 버전 바뀔 때 확인용으로 사용, 신뢰의 핵심은 content_sha256
    http_etag TEXT,
    http_last_modified TEXT,

    -- 원본 HTML bytes 자체의 hash
    -- HTML 전체 파일이 바뀌었는지 확인
    raw_sha256 VARCHAR(64) NOT NULL,

    -- 정규화한 실제 본문의 hash
    -- 실제 의미 있는 본문이 바뀌었는지 확인
    content_sha256 VARCHAR(64) NOT NULL,

    original_content BYTEA NOT NULL,

    normalized_content TEXT NOT NULL,

    -- 해당 version이 과거 version이 된 시각
    superseded_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_source_document_registry
        FOREIGN KEY (source_registry_id)
        REFERENCES source_registry (id)
        ON DELETE RESTRICT,

    CONSTRAINT uk_source_document_version
        UNIQUE (
            source_registry_id,
            document_version
        ),

    CONSTRAINT ck_source_document_version
        CHECK (
            document_version > 0
        ),

    CONSTRAINT ck_source_document_status
        CHECK (
            status IN (
                'ACTIVE',
                'SUPERSEDED'
            )
        ),

    CONSTRAINT ck_source_document_raw_sha256
        CHECK (
            raw_sha256 ~ '^[0-9a-f]{64}$'
        ),

    CONSTRAINT ck_source_document_content_sha256
        CHECK (
            content_sha256 ~ '^[0-9a-f]{64}$'
        ),

    CONSTRAINT ck_source_document_normalized_content
        CHECK (
            length(
                btrim(normalized_content)
            ) > 0
        ),

    CONSTRAINT ck_source_document_superseded_at
        CHECK (
            (
                status = 'ACTIVE'
                AND superseded_at IS NULL
            )
            OR
            (
                status = 'SUPERSEDED'
                AND superseded_at IS NOT NULL
            )
        )
);

-- 하나의 SourceRegistry는 ACTIVE SourceDocument를 최대 하나만 가질 수 있음
CREATE UNIQUE INDEX uk_source_document_one_active
    ON source_document (
        source_registry_id
    )
    WHERE status = 'ACTIVE';

CREATE INDEX idx_source_document_content_sha256
    ON source_document (
        content_sha256
    );

CREATE INDEX idx_source_document_status
    ON source_document (
        status
    );


INSERT INTO official_source_domain (
    domain,
    organization_name
)
VALUES
    (
        'fsc.go.kr',
        '금융위원회'
    ),
    (
        'counterscam112.go.kr',
        '경찰청 전기통신금융사기 통합대응단'
    ),
    (
        'kisa.kr',
        '한국인터넷진흥원'
    );


INSERT INTO source_registry (
    id,
    source_key,
    organization_name,
    official_domain,
    canonical_url,
    acquisition_type
)
VALUES
    (
        '20000000-0000-0000-0000-000000000001',
        'fsc-2026-alert',
        '금융위원회',
        'fsc.go.kr',
        'https://www.fsc.go.kr/no010101/86271',
        'HTML'
    ),
    (
        '20000000-0000-0000-0000-000000000002',
        'police-response',
        '경찰청 전기통신금융사기 통합대응단',
        'counterscam112.go.kr',
        'https://www.counterscam112.go.kr/board/CONTENT_000000000009.do',
        'HTML'
    ),
    (
        '20000000-0000-0000-0000-000000000003',
        'police-campaign',
        '경찰청 전기통신금융사기 통합대응단',
        'counterscam112.go.kr',
        'https://www.counterscam112.go.kr/campaign/index.html',
        'HTML'
    ),
    (
        '20000000-0000-0000-0000-000000000004',
        'police-reporting',
        '경찰청 전기통신금융사기 통합대응단',
        'counterscam112.go.kr',
        'https://www.counterscam112.go.kr/board/CONTENT_000000000010.do',
        'HTML'
    ),
    (
        '20000000-0000-0000-0000-000000000005',
        'fsc-card',
        '금융위원회',
        'fsc.go.kr',
        'https://www.fsc.go.kr/edu/cardnews?cnId=2361',
        'HTML'
    ),
    (
        '20000000-0000-0000-0000-000000000006',
        'kisa-118',
        '한국인터넷진흥원',
        'kisa.kr',
        'https://www.kisa.kr/118',
        'HTML'
    ),
    (
        '20000000-0000-0000-0000-000000000007',
        'fsc-personal-info',
        '금융위원회',
        'fsc.go.kr',
        'https://www.fsc.go.kr/edu/cardnews?cnId=1527',
        'HTML'
    );