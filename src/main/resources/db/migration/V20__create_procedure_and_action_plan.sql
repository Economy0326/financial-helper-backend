ALTER TABLE follow_up_question
    ADD COLUMN fact_key VARCHAR(64),
    ADD COLUMN input_type VARCHAR(32),
    ADD COLUMN required_for_decision BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN question_intent VARCHAR(128);

CREATE INDEX idx_follow_up_question_fact_key
    ON follow_up_question (consultation_id, case_input_revision, fact_key);

CREATE TABLE procedure_version (
    id UUID PRIMARY KEY,
    scenario VARCHAR(100) NOT NULL,
    institution VARCHAR(200) NOT NULL,
    product_type VARCHAR(100) NOT NULL,
    version INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    applicability_start_date DATE,
    applicability_end_date DATE,
    required_facts_json TEXT NOT NULL,
    condition_rules_json TEXT NOT NULL,
    action_steps_json TEXT NOT NULL,
    document_requirements_json TEXT NOT NULL,
    evidence_references_json TEXT NOT NULL,
    reviewed_contacts_json TEXT NOT NULL,
    reviewed_values_json TEXT NOT NULL,
    conditions_exceptions_json TEXT NOT NULL,
    review_notes TEXT,
    reviewed_by VARCHAR(200),
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_procedure_version_identity
        UNIQUE (scenario, institution, product_type, version),
    CONSTRAINT ck_procedure_version_status
        CHECK (status IN ('DRAFT', 'APPROVED', 'RETIRED')),
    CONSTRAINT ck_procedure_version_version
        CHECK (version > 0),
    CONSTRAINT ck_procedure_version_applicability
        CHECK (
            applicability_end_date IS NULL
            OR applicability_start_date IS NULL
            OR applicability_end_date >= applicability_start_date
        ),
    CONSTRAINT ck_procedure_version_required_facts_json
        CHECK (jsonb_typeof(required_facts_json::jsonb) = 'array'),
    CONSTRAINT ck_procedure_version_condition_rules_json
        CHECK (jsonb_typeof(condition_rules_json::jsonb) = 'array'),
    CONSTRAINT ck_procedure_version_action_steps_json
        CHECK (jsonb_typeof(action_steps_json::jsonb) = 'array'),
    CONSTRAINT ck_procedure_version_document_requirements_json
        CHECK (jsonb_typeof(document_requirements_json::jsonb) = 'array'),
    CONSTRAINT ck_procedure_version_evidence_references_json
        CHECK (jsonb_typeof(evidence_references_json::jsonb) = 'array'),
    CONSTRAINT ck_procedure_version_reviewed_contacts_json
        CHECK (jsonb_typeof(reviewed_contacts_json::jsonb) = 'array'),
    CONSTRAINT ck_procedure_version_reviewed_values_json
        CHECK (jsonb_typeof(reviewed_values_json::jsonb) = 'array'),
    CONSTRAINT ck_procedure_version_conditions_exceptions_json
        CHECK (jsonb_typeof(conditions_exceptions_json::jsonb) = 'array'),
    CONSTRAINT ck_procedure_version_approval_metadata
        CHECK (
            (status = 'APPROVED' AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL)
            OR status <> 'APPROVED'
        )
);

CREATE INDEX idx_procedure_version_lookup
    ON procedure_version (scenario, institution, product_type, status, version DESC);

CREATE TABLE financial_action_plan (
    id UUID PRIMARY KEY,
    consultation_id UUID NOT NULL,
    procedure_version_id UUID NOT NULL,
    confirmed_case_snapshot_id UUID NOT NULL,
    case_input_revision BIGINT NOT NULL,
    follow_up_answer_revision BIGINT NOT NULL,
    scenario VARCHAR(100) NOT NULL,
    plan_status VARCHAR(32) NOT NULL,
    plan_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_financial_action_plan_consultation
        FOREIGN KEY (consultation_id)
        REFERENCES consultation (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_financial_action_plan_procedure
        FOREIGN KEY (procedure_version_id)
        REFERENCES procedure_version (id),
    CONSTRAINT fk_financial_action_plan_snapshot
        FOREIGN KEY (confirmed_case_snapshot_id)
        REFERENCES confirmed_case_snapshot (id),
    CONSTRAINT uk_financial_action_plan_revision
        UNIQUE (
            consultation_id,
            procedure_version_id,
            case_input_revision,
            follow_up_answer_revision
        ),
    CONSTRAINT ck_financial_action_plan_status
        CHECK (plan_status IN ('READY', 'NEEDS_CLARIFICATION', 'UNSUPPORTED', 'FAILED')),
    CONSTRAINT ck_financial_action_plan_case_revision
        CHECK (case_input_revision >= 0),
    CONSTRAINT ck_financial_action_plan_followup_revision
        CHECK (follow_up_answer_revision >= 0),
    CONSTRAINT ck_financial_action_plan_json
        CHECK (jsonb_typeof(plan_json::jsonb) = 'object')
);

CREATE INDEX idx_financial_action_plan_consultation
    ON financial_action_plan (consultation_id, case_input_revision DESC, follow_up_answer_revision DESC);

INSERT INTO procedure_version (
    id,
    scenario,
    institution,
    product_type,
    version,
    status,
    applicability_start_date,
    applicability_end_date,
    required_facts_json,
    condition_rules_json,
    action_steps_json,
    document_requirements_json,
    evidence_references_json,
    reviewed_contacts_json,
    reviewed_values_json,
    conditions_exceptions_json,
    review_notes,
    reviewed_by,
    reviewed_at
)
VALUES (
    '6aa5b8b5-9c64-4b1e-a70b-1c8a3d8d0c41',
    'CARD_LOSS_UNAUTHORIZED_USE',
    '㈜KB국민카드',
    'PERSONAL_CREDIT_CARD',
    1,
    'APPROVED',
    DATE '2026-05-12',
    NULL,
    $$[
      {"key":"institution","type":"INSTITUTION","requiredForDecision":true},
      {"key":"productType","type":"ENUM","requiredForDecision":true},
      {"key":"cardLost","type":"BOOLEAN","requiredForDecision":true},
      {"key":"unauthorizedPayment","type":"BOOLEAN","requiredForDecision":true},
      {"key":"transactionType","type":"ENUM","requiredForDecision":true},
      {"key":"domestic","type":"BOOLEAN","requiredForDecision":true},
      {"key":"reported","type":"BOOLEAN","requiredForDecision":true},
      {"key":"incidentDate","type":"DATE","requiredForDecision":true}
    ]$$,
    $$[
      {"actionId":"report-loss","expression":{"op":"AND","conditions":[{"factKey":"cardLost","operator":"EQ","expectedValue":"TRUE"},{"factKey":"reported","operator":"EQ","expectedValue":"FALSE"}]}},
      {"actionId":"confirm-unauthorized-payment","expression":{"op":"AND","conditions":[{"factKey":"unauthorizedPayment","operator":"EQ","expectedValue":"TRUE"},{"factKey":"transactionType","operator":"EQ","expectedValue":"CREDIT_SALE"},{"factKey":"domestic","operator":"EQ","expectedValue":"TRUE"}]}},
      {"actionId":"submit-compensation-request","expression":{"op":"AND","conditions":[{"factKey":"unauthorizedPayment","operator":"EQ","expectedValue":"TRUE"},{"factKey":"reported","operator":"EQ","expectedValue":"TRUE"},{"factKey":"transactionType","operator":"EQ","expectedValue":"CREDIT_SALE"},{"factKey":"domestic","operator":"EQ","expectedValue":"TRUE"}]}},
      {"actionId":"review-investigation-result","expression":{"op":"AND","conditions":[{"factKey":"unauthorizedPayment","operator":"EQ","expectedValue":"TRUE"},{"factKey":"reported","operator":"EQ","expectedValue":"TRUE"}]}},
      {"actionId":"request-result-review","expression":{"factKey":"resultDisputed","operator":"EQ","expectedValue":"TRUE"}}
    ]$$,
    $$[
      {"actionId":"report-loss","order":1,"title":"분실·도난 신고","description":"카드 분실 또는 도난을 공식 채널로 신고합니다.","channelRef":"kb-card-loss-report-ars","evidenceRoles":["REPORT_CHANNEL"]},
      {"actionId":"confirm-unauthorized-payment","order":2,"title":"미인지 신용판매 확인","description":"본인이 하지 않은 국내 신용판매 결제를 확인하고 접수 대상인지 확인합니다.","channelRef":"kb-card-compensation-process","evidenceRoles":["PROCEDURE"]},
      {"actionId":"submit-compensation-request","order":3,"title":"부정사용 보상 접수","description":"신고 후 보상신청서와 해당 사건의 확인 서류를 공식 접수 채널로 제출합니다.","channelRef":"kb-unauthorized-compensation-form-260209","evidenceRoles":["FORM"]},
      {"actionId":"review-investigation-result","order":4,"title":"조사·결정·통보 결과 확인","description":"접수 후 조사와 결정·통보 결과를 확인합니다.","channelRef":"crefia-card-loss-compensation-guideline-221021","evidenceRoles":["INVESTIGATION"]},
      {"actionId":"request-result-review","order":5,"title":"결과 이의 확인","description":"결과에 이견이 있으면 공식 접수 채널에 재확인 방법을 문의합니다.","channelRef":"kb-card-compensation-process","evidenceRoles":["FOLLOW_UP"]}
    ]$$,
    $$[
      {"documentId":"unauthorized-compensation-form","title":"부정사용대금 보상신청서","status":"CONDITIONAL","condition":{"factKey":"unauthorizedPayment","operator":"EQ","expectedValue":"TRUE"},"evidenceRef":"kb-unauthorized-compensation-form-260209"},
      {"documentId":"case-specific-supporting-documents","title":"사건별 추가 확인 서류","status":"ON_REQUEST","condition":{"factKey":"unauthorizedPayment","operator":"EQ","expectedValue":"TRUE"},"evidenceRef":"kb-card-compensation-process"}
    ]$$,
    $$[
      {"sourceKey":"kb-personal-card-terms-260402","documentVersion":1,"expectedRawSha256":"fa7261248a6ac0b30ada429f4a37947e6a8b31101db981ad66261c2ca29f3c33","articleReference":"제39조","role":"TERMS"},
      {"sourceKey":"kb-personal-card-terms-260402","documentVersion":1,"expectedRawSha256":"fa7261248a6ac0b30ada429f4a37947e6a8b31101db981ad66261c2ca29f3c33","articleReference":"제40조","role":"TERMS"},
      {"sourceKey":"crefia-card-loss-compensation-guideline-221021","documentVersion":1,"expectedRawSha256":"bfffe9832f9d3ba7db80e613a120c332ad4e909d2eb8629917dc0cb695011dd6","articleReference":"제4조","role":"GUIDELINE"},
      {"sourceKey":"crefia-card-loss-compensation-guideline-221021","documentVersion":1,"expectedRawSha256":"bfffe9832f9d3ba7db80e613a120c332ad4e909d2eb8629917dc0cb695011dd6","articleReference":"제12조","role":"GUIDELINE"},
      {"sourceKey":"kb-unauthorized-compensation-form-260209","documentVersion":1,"expectedRawSha256":"e3a566e95f166bbbc07fd667e47c3dbd60bb14147626ffae5f2093721b3c6c02","pageReference":"p.2","role":"FORM"},
      {"sourceKey":"kb-card-compensation-process","documentVersion":1,"expectedRawSha256":"e1e92c22c4e104e931286e8f44395654f4abd09392d281c36d7d2417a85e4b44","role":"PROCEDURE"},
      {"sourceKey":"kb-card-loss-report-ars","documentVersion":1,"expectedRawSha256":"76851ca2f7305a057e75bb914c37ddd6042ba9d574082288d1c2eae9fbc4172d","role":"REPORT_CHANNEL"}
    ]$$,
    $$[]$$,
    $$[]$$,
    $$[
      "분실·도난 신고와 부정사용 보상 접수는 별도 단계로 취급한다.",
      "K5 신청서의 역사적 시행일이 확인되지 않아 incidentDate가 있는 경우 해당 근거의 적용 여부를 추측하지 않는다.",
      "카드번호·계좌번호·비밀번호 등 민감정보는 Procedure 입력 fact로 요구하지 않는다."
    ]$$,
    'CARD v1은 Work 1에서 검증된 KB/C2/K5 공식 원문과 활성 HTML에만 바인딩한다.',
    'human-reviewed-work-1',
    TIMESTAMPTZ '2026-09-15 00:00:00+00'
)
ON CONFLICT (scenario, institution, product_type, version) DO NOTHING;
