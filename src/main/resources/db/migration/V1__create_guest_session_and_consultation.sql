CREATE TABLE guest_session (
    -- UUID => 충돌 가능성이 매우 낮은 긴 식별자
    id UUID PRIMARY KEY,
    token_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    -- expires_at => 세션 만료 시간
    expires_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uk_guest_session_token_hash
        UNIQUE (token_hash)
);


CREATE TABLE consultation (
    id UUID PRIMARY KEY,

    guest_session_id UUID NOT NULL,

    -- category와 situation_text는 생성 직후 상담의 카테고리를 선택하지 않은 상태일 수 있으므로 NULL 허용
    category VARCHAR(32),
    situation_text TEXT,

    -- status => 상담 진행 상태
    status VARCHAR(32) NOT NULL,

    -- current_step => 상담 진행 단계
    current_step VARCHAR(32) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    -- GuestSession 1 : Consultation N
    CONSTRAINT fk_consultation_guest_session
        FOREIGN KEY (guest_session_id)
        REFERENCES guest_session (id)
);

-- Guest의 진행 중 Consultation 조회 시 guest_session_id 조건 검색을 빠르게 하기 위한 인덱스
CREATE INDEX idx_consultation_guest_session_id
    ON consultation (guest_session_id);

-- IN_PROGRESS 등 Consultation 상태 기준 조회를 빠르게 하기 위한 인덱스
CREATE INDEX idx_consultation_status
    ON consultation (status);