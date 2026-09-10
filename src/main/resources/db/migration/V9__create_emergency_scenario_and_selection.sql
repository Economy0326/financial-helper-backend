-- 서버가 보관하는 긴급대응 기준 데이터
CREATE TABLE emergency_scenario (
    id UUID PRIMARY KEY,

    emergency_type VARCHAR(32) NOT NULL,
    scenario_key VARCHAR(64) NOT NULL,
    display_order INTEGER NOT NULL,

    type_title VARCHAR(100) NOT NULL,
    type_description VARCHAR(300) NOT NULL,
    type_icon VARCHAR(32) NOT NULL,

    payload_json TEXT NOT NULL,

    -- 공식 원문 검토 완료일. V2 검증 전에는 NULL.
    source_checked_at DATE,

    CONSTRAINT uk_emergency_scenario_type
        UNIQUE (emergency_type),

    CONSTRAINT uk_emergency_scenario_key
        UNIQUE (scenario_key),

    CONSTRAINT uk_emergency_scenario_display_order
        UNIQUE (display_order),

    CONSTRAINT ck_emergency_scenario_type
        CHECK (
            emergency_type IN (
                'TRANSFER',
                'UNKNOWN_PAYMENT',
                'SUSPICIOUS_APP',
                'PERSONAL_INFO',
                'UNKNOWN'
            )
        ),

    CONSTRAINT ck_emergency_scenario_display_order
        CHECK (display_order > 0),

    CONSTRAINT ck_emergency_scenario_payload_json
        CHECK (
            jsonb_typeof(payload_json::jsonb) = 'object'
        )
);

-- 각 Guest가 어떤 피해 유형을 선택했는지 저장하는 사용자 상태 데이터
CREATE TABLE emergency_selection (
    id UUID PRIMARY KEY,

    guest_session_id UUID NOT NULL,
    selected_type VARCHAR(32) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_emergency_selection_guest_session
        FOREIGN KEY (guest_session_id)
        REFERENCES guest_session (id)
        ON DELETE CASCADE,

    CONSTRAINT uk_emergency_selection_guest_session
        UNIQUE (guest_session_id),

    CONSTRAINT ck_emergency_selection_type
        CHECK (
            selected_type IN (
                'TRANSFER',
                'UNKNOWN_PAYMENT',
                'SUSPICIOUS_APP',
                'PERSONAL_INFO',
                'UNKNOWN'
            )
        )
);

INSERT INTO emergency_scenario (
    id,
    emergency_type,
    scenario_key,
    display_order,
    type_title,
    type_description,
    type_icon,
    payload_json,
    source_checked_at
)
VALUES (
    '10000000-0000-0000-0000-000000000001',
    'TRANSFER',
    'TRANSFER_SCENARIO',
    1,
    '돈을 송금했어요',
    '보이스피싱 · 사기 계좌로 송금했을 때',
    'transfer',
    $emergency_payload$
{
  "title": "지금 바로 이렇게 하세요",
  "description": "추가 피해를 막기 위해 공식 신고·금융회사 채널을 먼저 이용하세요.",
  "actionsToDo": [
    {
      "id": "transfer-do-1",
      "title": "금융회사나 112에 즉시 신고하세요.",
      "description": "금융 피해가 발생했다면 본인 또는 상대 계좌의 금융회사나 112에 지체 없이 연락해 지급정지 등 필요한 조치를 요청하세요.",
      "sourceIds": [
        "fsc-2026-alert",
        "police-campaign"
      ]
    },
    {
      "id": "transfer-do-2",
      "title": "공식 안내에 따라 피해 대응을 이어가세요.",
      "description": "경찰청 통합대응단은 피해 신고 접수와 계좌 지급정지 지원 등 실시간 피해 예방 조치를 지원합니다.",
      "sourceIds": [
        "police-response"
      ]
    },
    {
      "id": "transfer-do-3",
      "title": "신고에 필요한 기록을 준비하세요.",
      "description": "문자·통화내역·발신번호·녹음·송금을 요구받은 계좌 등 확인 가능한 자료를 정리하세요.",
      "sourceIds": [
        "police-reporting"
      ]
    }
  ],
  "actionsToAvoid": [
    {
      "id": "transfer-avoid-1",
      "title": "추가 송금이나 개인정보 제공에 응하지 마세요.",
      "description": "기관이나 금융회사를 사칭한 상대방이 송금·개인정보를 요구하더라도 그대로 응하지 마세요.",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "transfer-avoid-2",
      "title": "의심스러운 링크나 앱을 이용하지 마세요.",
      "description": "출처가 의심스러운 URL을 누르거나 상대방이 안내한 앱을 추가로 설치하지 마세요.",
      "sourceIds": [
        "fsc-2026-alert"
      ]
    }
  ],
  "contacts": [
    {
      "id": "police",
      "name": "경찰 신고",
      "description": "보이스피싱 등 전기통신금융사기 피해가 의심되면 112에 신고하세요.",
      "phoneLabel": "112",
      "phoneHref": "tel:112",
      "icon": "police",
      "sourceIds": [
        "police-campaign"
      ]
    },
    {
      "id": "financial-company",
      "name": "이용 중인 금융회사",
      "description": "본인 또는 상대 계좌와 관련된 금융회사 공식 고객센터에 지급정지 등 필요한 조치를 문의하세요.",
      "phoneLabel": null,
      "phoneHref": null,
      "icon": "financial-company",
      "sourceIds": [
        "fsc-2026-alert"
      ]
    }
  ],
  "evidence": [
    {
      "id": "message-screenshot",
      "label": "문자 / 메시지 화면",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "caller-number",
      "label": "발신번호",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "call-history",
      "label": "통화 기록",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "call-recording",
      "label": "통화 녹음",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "requested-account",
      "label": "상대방이 알려준 계좌정보",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "requested-information",
      "label": "요구받은 정보의 종류",
      "sourceIds": [
        "police-reporting"
      ]
    }
  ],
  "sources": [
    {
      "id": "fsc-2026-alert",
      "organization": "금융위원회",
      "title": "빗썸 오지급 보상 안내의 URL링크는 사기입니다. - 스미싱 소비자경보",
      "publishedAt": "2026-02-12",
      "reference": "금융위원회 정책일반 86271"
    },
    {
      "id": "police-response",
      "organization": "경찰청 전기통신금융사기 통합대응단",
      "title": "전기통신금융사기 통합대응단 소개",
      "publishedAt": null,
      "reference": "경찰청 전기통신금융사기 통합대응단 CONTENT_000000000009"
    },
    {
      "id": "police-campaign",
      "organization": "경찰청 전기통신금융사기 통합대응단",
      "title": "보이스피싱, 이것만 기억해!",
      "publishedAt": null,
      "reference": "경찰청 전기통신금융사기 통합대응단 캠페인 '보이스피싱, 이것만 기억해!'"
    },
    {
      "id": "police-reporting",
      "organization": "경찰청 전기통신금융사기 통합대응단",
      "title": "제보방법 안내",
      "publishedAt": null,
      "reference": "경찰청 전기통신금융사기 통합대응단 CONTENT_000000000010"
    }
  ]
}
$emergency_payload$,
    NULL
);

INSERT INTO emergency_scenario (
    id,
    emergency_type,
    scenario_key,
    display_order,
    type_title,
    type_description,
    type_icon,
    payload_json,
    source_checked_at
)
VALUES (
    '10000000-0000-0000-0000-000000000002',
    'UNKNOWN_PAYMENT',
    'UNKNOWN_PAYMENT_SCENARIO',
    2,
    '모르는 결제가 발생했어요',
    '카드 · 계좌에서 본인이 하지 않은 결제가 발생했을 때',
    'payment',
    $emergency_payload$
{
  "title": "지금 바로 이렇게 하세요",
  "description": "본인이 하지 않은 거래라면 해당 금융회사에 신속히 알리고 필요한 조치를 확인하세요.",
  "actionsToDo": [
    {
      "id": "payment-do-1",
      "title": "카드사 또는 금융회사에 즉시 알리세요.",
      "description": "본인이 하지 않은 거래임을 해당 카드사나 금융회사의 공식 고객센터에 알리고 이용정지·조사 등 필요한 절차를 확인하세요.",
      "sourceIds": [
        "fsc-card",
        "fsc-2026-alert"
      ]
    },
    {
      "id": "payment-do-2",
      "title": "보이스피싱이 의심되면 112에도 신고하세요.",
      "description": "전기통신금융사기로 인한 금융 피해가 의심되면 112를 통해 피해 사실을 알리고 필요한 지급정지 지원을 요청하세요.",
      "sourceIds": [
        "police-campaign",
        "police-response"
      ]
    },
    {
      "id": "payment-do-3",
      "title": "관련 기록을 준비하세요.",
      "description": "결제내역, 문자·통화기록, 발신번호 등 확인 가능한 자료를 정리하세요.",
      "sourceIds": [
        "police-reporting"
      ]
    }
  ],
  "actionsToAvoid": [
    {
      "id": "payment-avoid-1",
      "title": "신고를 미루지 마세요.",
      "description": "카드 분실·도난이나 부정사용이 의심되면 카드사에 신속히 신고하세요.",
      "sourceIds": [
        "fsc-card"
      ]
    },
    {
      "id": "payment-avoid-2",
      "title": "취소를 도와준다는 연락에 인증정보를 주지 마세요.",
      "description": "공식 기관·금융기관을 사칭하며 개인정보나 송금을 요구하는 연락에 응하지 마세요.",
      "sourceIds": [
        "police-reporting"
      ]
    }
  ],
  "contacts": [
    {
      "id": "card-company",
      "name": "이용 중인 카드사",
      "description": "본인이 하지 않은 카드 결제라면 카드사의 공식 고객센터에 즉시 확인하세요.",
      "phoneLabel": null,
      "phoneHref": null,
      "icon": "card-company",
      "sourceIds": [
        "fsc-card"
      ]
    },
    {
      "id": "financial-company",
      "name": "이용 중인 금융회사",
      "description": "계좌 거래라면 해당 금융회사의 공식 고객센터에 확인하고 필요한 조치를 문의하세요.",
      "phoneLabel": null,
      "phoneHref": null,
      "icon": "financial-company",
      "sourceIds": [
        "fsc-2026-alert"
      ]
    },
    {
      "id": "police",
      "name": "경찰 신고",
      "description": "보이스피싱 등 전기통신금융사기 피해가 의심되면 112에 신고하세요.",
      "phoneLabel": "112",
      "phoneHref": "tel:112",
      "icon": "police",
      "sourceIds": [
        "police-campaign"
      ]
    }
  ],
  "evidence": [
    {
      "id": "message-screenshot",
      "label": "문자 / 메시지 화면",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "caller-number",
      "label": "발신번호",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "call-history",
      "label": "통화 기록",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "call-recording",
      "label": "통화 녹음",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "requested-account",
      "label": "상대방이 알려준 계좌정보",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "requested-information",
      "label": "요구받은 정보의 종류",
      "sourceIds": [
        "police-reporting"
      ]
    }
  ],
  "sources": [
    {
      "id": "fsc-card",
      "organization": "금융위원회",
      "title": "휴가철 카드분실, 누군가 내 신용카드를 사용했다면?",
      "publishedAt": "2024-08-08",
      "reference": "금융위원회 카드뉴스 cnId=2361"
    },
    {
      "id": "fsc-2026-alert",
      "organization": "금융위원회",
      "title": "빗썸 오지급 보상 안내의 URL링크는 사기입니다. - 스미싱 소비자경보",
      "publishedAt": "2026-02-12",
      "reference": "금융위원회 정책일반 86271"
    },
    {
      "id": "police-campaign",
      "organization": "경찰청 전기통신금융사기 통합대응단",
      "title": "보이스피싱, 이것만 기억해!",
      "publishedAt": null,
      "reference": "경찰청 전기통신금융사기 통합대응단 캠페인 '보이스피싱, 이것만 기억해!'"
    },
    {
      "id": "police-response",
      "organization": "경찰청 전기통신금융사기 통합대응단",
      "title": "전기통신금융사기 통합대응단 소개",
      "publishedAt": null,
      "reference": "경찰청 전기통신금융사기 통합대응단 CONTENT_000000000009"
    },
    {
      "id": "police-reporting",
      "organization": "경찰청 전기통신금융사기 통합대응단",
      "title": "제보방법 안내",
      "publishedAt": null,
      "reference": "경찰청 전기통신금융사기 통합대응단 CONTENT_000000000010"
    }
  ]
}
$emergency_payload$,
    NULL
);

INSERT INTO emergency_scenario (
    id,
    emergency_type,
    scenario_key,
    display_order,
    type_title,
    type_description,
    type_icon,
    payload_json,
    source_checked_at
)
VALUES (
    '10000000-0000-0000-0000-000000000003',
    'SUSPICIOUS_APP',
    'SUSPICIOUS_APP_SCENARIO',
    3,
    '수상한 앱을 설치했어요',
    '원격제어 앱 · 악성 앱 설치가 의심될 때',
    'app',
    $emergency_payload$
{
  "title": "지금 바로 이렇게 하세요",
  "description": "의심 기기에서 추가 금융행동을 멈추고 안전한 채널로 도움을 요청하세요.",
  "actionsToDo": [
    {
      "id": "app-do-1",
      "title": "의심 기기에서 금융정보 입력을 멈추세요.",
      "description": "악성 앱이 의심되는 기기에서는 비밀번호·인증번호 등 금융정보를 추가로 입력하지 마세요.",
      "sourceIds": [
        "fsc-2026-alert"
      ]
    },
    {
      "id": "app-do-2",
      "title": "통신을 끊고 안전한 방법으로 신고하세요.",
      "description": "악성 앱이 의심되면 비행기 모드 등으로 통신을 끊고, 가능하면 다른 안전한 기기나 경찰서 방문을 통해 112·금융회사에 연락하세요.",
      "sourceIds": [
        "fsc-2026-alert"
      ]
    },
    {
      "id": "app-do-3",
      "title": "118에서 스미싱·악성코드 상담을 받을 수 있어요.",
      "description": "한국인터넷진흥원 118은 해킹·바이러스, 스미싱 등 디지털 피해 관련 상담을 제공합니다.",
      "sourceIds": [
        "kisa-118"
      ]
    }
  ],
  "actionsToAvoid": [
    {
      "id": "app-avoid-1",
      "title": "추가 앱을 설치하지 마세요.",
      "description": "문자나 상대방이 전달한 링크를 통해 출처가 불분명한 앱을 추가로 설치하지 마세요.",
      "sourceIds": [
        "fsc-2026-alert"
      ]
    },
    {
      "id": "app-avoid-2",
      "title": "의심 기기에서 인증·송금을 진행하지 마세요.",
      "description": "악성 앱이 통화나 금융정보를 가로챌 수 있으므로 의심 기기에서 추가 인증이나 송금을 진행하지 마세요.",
      "sourceIds": [
        "fsc-2026-alert"
      ]
    }
  ],
  "contacts": [
    {
      "id": "police",
      "name": "경찰 신고",
      "description": "금융 피해나 보이스피싱이 의심되면 안전한 방법으로 112에 신고하세요.",
      "phoneLabel": "112",
      "phoneHref": "tel:112",
      "icon": "police",
      "sourceIds": [
        "police-campaign",
        "fsc-2026-alert"
      ]
    },
    {
      "id": "kisa",
      "name": "KISA 118 상담",
      "description": "스미싱·악성코드·해킹 등 디지털 피해 관련 상담을 받을 수 있습니다.",
      "phoneLabel": "118",
      "phoneHref": "tel:118",
      "icon": "official",
      "sourceIds": [
        "kisa-118"
      ]
    },
    {
      "id": "financial-company",
      "name": "이용 중인 금융회사",
      "description": "금융 피해가 의심되면 해당 금융회사의 공식 고객센터에도 필요한 조치를 문의하세요.",
      "phoneLabel": null,
      "phoneHref": null,
      "icon": "financial-company",
      "sourceIds": [
        "fsc-2026-alert"
      ]
    }
  ],
  "evidence": [
    {
      "id": "message-screenshot",
      "label": "문자 / 메시지 화면",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "caller-number",
      "label": "발신번호",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "call-history",
      "label": "통화 기록",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "call-recording",
      "label": "통화 녹음",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "requested-account",
      "label": "상대방이 알려준 계좌정보",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "requested-information",
      "label": "요구받은 정보의 종류",
      "sourceIds": [
        "police-reporting"
      ]
    }
  ],
  "sources": [
    {
      "id": "fsc-2026-alert",
      "organization": "금융위원회",
      "title": "빗썸 오지급 보상 안내의 URL링크는 사기입니다. - 스미싱 소비자경보",
      "publishedAt": "2026-02-12",
      "reference": "금융위원회 정책일반 86271"
    },
    {
      "id": "kisa-118",
      "organization": "한국인터넷진흥원",
      "title": "118 상담 서비스",
      "publishedAt": null,
      "reference": "한국인터넷진흥원 118 상담 서비스"
    },
    {
      "id": "police-campaign",
      "organization": "경찰청 전기통신금융사기 통합대응단",
      "title": "보이스피싱, 이것만 기억해!",
      "publishedAt": null,
      "reference": "경찰청 전기통신금융사기 통합대응단 캠페인 '보이스피싱, 이것만 기억해!'"
    },
    {
      "id": "police-reporting",
      "organization": "경찰청 전기통신금융사기 통합대응단",
      "title": "제보방법 안내",
      "publishedAt": null,
      "reference": "경찰청 전기통신금융사기 통합대응단 CONTENT_000000000010"
    }
  ]
}
$emergency_payload$,
    NULL
);

INSERT INTO emergency_scenario (
    id,
    emergency_type,
    scenario_key,
    display_order,
    type_title,
    type_description,
    type_icon,
    payload_json,
    source_checked_at
)
VALUES (
    '10000000-0000-0000-0000-000000000004',
    'PERSONAL_INFO',
    'PERSONAL_INFO_SCENARIO',
    4,
    '개인정보를 알려줬어요',
    '신분정보 · 비밀번호 · 인증정보 등이 노출되었을 때',
    'personal-info',
    $emergency_payload$
{
  "title": "지금 바로 이렇게 하세요",
  "description": "명의도용이나 추가 금융피해를 막기 위한 공식 보호 절차를 확인하세요.",
  "actionsToDo": [
    {
      "id": "personal-do-1",
      "title": "개인정보노출자 사고예방시스템을 확인하세요.",
      "description": "신분증 분실이나 보이스피싱 등으로 개인정보 노출이 우려되면 금융감독원 개인정보노출자 사고예방시스템 등록을 검토하세요.",
      "sourceIds": [
        "fsc-personal-info"
      ]
    },
    {
      "id": "personal-do-2",
      "title": "금융 피해가 있으면 금융회사나 112에 즉시 신고하세요.",
      "description": "자금 이체 등 금융 피해가 발생했다면 해당 금융회사나 112에 지체 없이 신고하고 지급정지 등 필요한 조치를 요청하세요.",
      "sourceIds": [
        "fsc-2026-alert",
        "police-campaign"
      ]
    },
    {
      "id": "personal-do-3",
      "title": "관련 연락 기록을 준비하세요.",
      "description": "상대방의 발신번호, 문자·통화내역, 요구받은 정보의 종류 등 확인 가능한 자료를 정리하세요.",
      "sourceIds": [
        "police-reporting"
      ]
    }
  ],
  "actionsToAvoid": [
    {
      "id": "personal-avoid-1",
      "title": "추가 개인정보나 인증정보를 제공하지 마세요.",
      "description": "공식 기관·금융기관을 사칭하며 개인정보나 송금을 요구하는 연락에 응하지 마세요.",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "personal-avoid-2",
      "title": "의심스러운 URL을 누르지 마세요.",
      "description": "개인정보 확인이나 피해 조회를 이유로 전달된 출처 불명의 링크를 이용하지 마세요.",
      "sourceIds": [
        "fsc-2026-alert"
      ]
    }
  ],
  "contacts": [
    {
      "id": "financial-company",
      "name": "이용 중인 금융회사",
      "description": "금융계정이나 거래에 영향이 우려되면 해당 금융회사의 공식 고객센터에 문의하세요.",
      "phoneLabel": null,
      "phoneHref": null,
      "icon": "financial-company",
      "sourceIds": [
        "fsc-2026-alert"
      ]
    },
    {
      "id": "police",
      "name": "경찰 신고",
      "description": "보이스피싱 등 전기통신금융사기 피해가 의심되면 112에 신고하세요.",
      "phoneLabel": "112",
      "phoneHref": "tel:112",
      "icon": "police",
      "sourceIds": [
        "police-campaign"
      ]
    },
    {
      "id": "kisa",
      "name": "KISA 118 상담",
      "description": "개인정보 침해와 디지털 피해 관련 상담을 받을 수 있습니다.",
      "phoneLabel": "118",
      "phoneHref": "tel:118",
      "icon": "official",
      "sourceIds": [
        "kisa-118"
      ]
    }
  ],
  "evidence": [
    {
      "id": "message-screenshot",
      "label": "문자 / 메시지 화면",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "caller-number",
      "label": "발신번호",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "call-history",
      "label": "통화 기록",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "call-recording",
      "label": "통화 녹음",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "requested-account",
      "label": "상대방이 알려준 계좌정보",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "requested-information",
      "label": "요구받은 정보의 종류",
      "sourceIds": [
        "police-reporting"
      ]
    }
  ],
  "sources": [
    {
      "id": "fsc-personal-info",
      "organization": "금융위원회",
      "title": "개인정보가 노출된 것 같다면 이렇게! (개인정보노출자 사고예방시스템)",
      "publishedAt": null,
      "reference": "금융위원회 웹툰 cnId=1527"
    },
    {
      "id": "fsc-2026-alert",
      "organization": "금융위원회",
      "title": "빗썸 오지급 보상 안내의 URL링크는 사기입니다. - 스미싱 소비자경보",
      "publishedAt": "2026-02-12",
      "reference": "금융위원회 정책일반 86271"
    },
    {
      "id": "police-campaign",
      "organization": "경찰청 전기통신금융사기 통합대응단",
      "title": "보이스피싱, 이것만 기억해!",
      "publishedAt": null,
      "reference": "경찰청 전기통신금융사기 통합대응단 캠페인 '보이스피싱, 이것만 기억해!'"
    },
    {
      "id": "police-reporting",
      "organization": "경찰청 전기통신금융사기 통합대응단",
      "title": "제보방법 안내",
      "publishedAt": null,
      "reference": "경찰청 전기통신금융사기 통합대응단 CONTENT_000000000010"
    },
    {
      "id": "kisa-118",
      "organization": "한국인터넷진흥원",
      "title": "118 상담 서비스",
      "publishedAt": null,
      "reference": "한국인터넷진흥원 118 상담 서비스"
    }
  ]
}
$emergency_payload$,
    NULL
);

INSERT INTO emergency_scenario (
    id,
    emergency_type,
    scenario_key,
    display_order,
    type_title,
    type_description,
    type_icon,
    payload_json,
    source_checked_at
)
VALUES (
    '10000000-0000-0000-0000-000000000005',
    'UNKNOWN',
    'COMMON_EMERGENCY_SCENARIO',
    5,
    '잘 모르겠어요',
    '어떤 피해인지 정확히 판단하기 어려울 때',
    'unknown',
    $emergency_payload$
{
  "title": "지금 바로 이렇게 하세요",
  "description": "피해 유형을 억지로 추정하지 않고, 현재 확인된 상황에 맞는 공식 채널을 이용하세요.",
  "actionsToDo": [
    {
      "id": "unknown-do-1",
      "title": "돈이 빠져나갔다면 금융회사나 112에 연락하세요.",
      "description": "송금·결제 등 금융 피해가 확인되면 관련 금융회사나 112에 신속히 연락해 필요한 조치를 문의하세요.",
      "sourceIds": [
        "fsc-2026-alert",
        "police-campaign"
      ]
    },
    {
      "id": "unknown-do-2",
      "title": "수상한 링크나 앱이 관련됐다면 118에 상담하세요.",
      "description": "스미싱·악성코드 등 디지털 피해가 의심되면 한국인터넷진흥원 118에서 상담을 받을 수 있습니다.",
      "sourceIds": [
        "kisa-118"
      ]
    },
    {
      "id": "unknown-do-3",
      "title": "연락·거래 관련 기록을 정리하세요.",
      "description": "발신번호, 문자·통화내역, 상대방이 알려준 계좌 등 확인 가능한 자료를 준비하세요.",
      "sourceIds": [
        "police-reporting"
      ]
    }
  ],
  "actionsToAvoid": [
    {
      "id": "unknown-avoid-1",
      "title": "피해 유형을 스스로 단정해 추가 행동하지 마세요.",
      "description": "상대방의 지시에 따라 추가 송금·개인정보 제공을 하지 말고 공식 기관이나 금융회사를 통해 확인하세요.",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "unknown-avoid-2",
      "title": "출처가 불분명한 링크나 앱을 이용하지 마세요.",
      "description": "의심스러운 URL을 누르거나 전달받은 앱을 설치하지 마세요.",
      "sourceIds": [
        "fsc-2026-alert"
      ]
    }
  ],
  "contacts": [
    {
      "id": "police",
      "name": "경찰 신고",
      "description": "전기통신금융사기 피해가 의심되면 112에 신고하세요.",
      "phoneLabel": "112",
      "phoneHref": "tel:112",
      "icon": "police",
      "sourceIds": [
        "police-campaign"
      ]
    },
    {
      "id": "financial-company",
      "name": "이용 중인 금융회사",
      "description": "송금·결제 등 금융 피해가 있다면 관련 금융회사의 공식 고객센터에 문의하세요.",
      "phoneLabel": null,
      "phoneHref": null,
      "icon": "financial-company",
      "sourceIds": [
        "fsc-2026-alert"
      ]
    },
    {
      "id": "kisa",
      "name": "KISA 118 상담",
      "description": "스미싱·악성코드·개인정보 침해 등 디지털 피해 관련 상담을 받을 수 있습니다.",
      "phoneLabel": "118",
      "phoneHref": "tel:118",
      "icon": "official",
      "sourceIds": [
        "kisa-118"
      ]
    }
  ],
  "evidence": [
    {
      "id": "message-screenshot",
      "label": "문자 / 메시지 화면",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "caller-number",
      "label": "발신번호",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "call-history",
      "label": "통화 기록",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "call-recording",
      "label": "통화 녹음",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "requested-account",
      "label": "상대방이 알려준 계좌정보",
      "sourceIds": [
        "police-reporting"
      ]
    },
    {
      "id": "requested-information",
      "label": "요구받은 정보의 종류",
      "sourceIds": [
        "police-reporting"
      ]
    }
  ],
  "sources": [
    {
      "id": "fsc-2026-alert",
      "organization": "금융위원회",
      "title": "빗썸 오지급 보상 안내의 URL링크는 사기입니다. - 스미싱 소비자경보",
      "publishedAt": "2026-02-12",
      "reference": "금융위원회 정책일반 86271"
    },
    {
      "id": "police-campaign",
      "organization": "경찰청 전기통신금융사기 통합대응단",
      "title": "보이스피싱, 이것만 기억해!",
      "publishedAt": null,
      "reference": "경찰청 전기통신금융사기 통합대응단 캠페인 '보이스피싱, 이것만 기억해!'"
    },
    {
      "id": "police-reporting",
      "organization": "경찰청 전기통신금융사기 통합대응단",
      "title": "제보방법 안내",
      "publishedAt": null,
      "reference": "경찰청 전기통신금융사기 통합대응단 CONTENT_000000000010"
    },
    {
      "id": "kisa-118",
      "organization": "한국인터넷진흥원",
      "title": "118 상담 서비스",
      "publishedAt": null,
      "reference": "한국인터넷진흥원 118 상담 서비스"
    }
  ]
}
$emergency_payload$,
    NULL
);