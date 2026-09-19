-- Branch-specific CARD procedures.  The existing CARD_LOSS_UNAUTHORIZED_USE
-- row remains the live baseline; these rows are selected deterministically
-- from confirmed facts and never by an LLM.

INSERT INTO procedure_version (
    id, scenario, institution, product_type, version, status,
    applicability_start_date, applicability_end_date,
    required_facts_json, condition_rules_json, action_steps_json,
    document_requirements_json, evidence_references_json,
    reviewed_contacts_json, reviewed_values_json, conditions_exceptions_json,
    review_notes, reviewed_by, reviewed_at
)
VALUES
(
    'b5f7cada-0410-46f9-9834-e558c50e6bb8',
    'CARD_LOSS_ONLY', '㈜KB국민카드', 'PERSONAL_CREDIT_CARD', 1, 'APPROVED',
    DATE '2026-05-12', NULL,
    $$[
      {"key":"institution","type":"INSTITUTION","requiredForDecision":true},
      {"key":"productType","type":"ENUM","requiredForDecision":true},
      {"key":"cardLost","type":"BOOLEAN","requiredForDecision":true},
      {"key":"reported","type":"BOOLEAN","requiredForDecision":true},
      {"key":"incidentDate","type":"DATE","requiredForDecision":true}
    ]$$,
    $$[
      {"actionId":"report-loss","expression":{"op":"AND","conditions":[{"factKey":"cardLost","operator":"EQ","expectedValue":"TRUE"},{"factKey":"reported","operator":"EQ","expectedValue":"FALSE"}]}}
    ]$$,
    $$[
      {"actionId":"report-loss","order":1,"title":"분실·도난 신고","description":"카드 분실 또는 도난을 KB국민카드 공식 채널로 신고합니다.","channelRef":"kb-card-loss-report-ars","evidenceRoles":["REPORT_CHANNEL"]}
    ]$$,
    $$[]$$,
    $$[
      {"sourceKey":"kb-personal-card-terms-260402","documentVersion":1,"expectedRawSha256":"fa7261248a6ac0b30ada429f4a37947e6a8b31101db981ad66261c2ca29f3c33","articleReference":"제40조","role":"TERMS"},
      {"sourceKey":"kb-card-loss-report-ars","documentVersion":1,"expectedRawSha256":"76851ca2f7305a057e75bb914c37ddd6042ba9d574082288d1c2eae9fbc4172d","role":"REPORT_CHANNEL"}
    ]$$,
    $$[]$$, $$[]$$,
    $$["미인지 결제가 확인되지 않은 분실·도난의 신고 단계만 다룬다."]$$,
    'CARD loss-only branch is limited to reviewed loss-report guidance.', 'human-reviewed-card-breadth', TIMESTAMPTZ '2026-09-19 00:00:00+09'
),
(
    '863f9a92-6332-4c1e-85ec-643e07b8a902',
    'CARD_HELD_UNAUTHORIZED_USE', '㈜KB국민카드', 'PERSONAL_CREDIT_CARD', 1, 'APPROVED',
    DATE '2026-05-12', NULL,
    $$[
      {"key":"institution","type":"INSTITUTION","requiredForDecision":true},
      {"key":"productType","type":"ENUM","requiredForDecision":true},
      {"key":"cardLost","type":"BOOLEAN","requiredForDecision":true},
      {"key":"unauthorizedPayment","type":"BOOLEAN","requiredForDecision":true},
      {"key":"transactionType","type":"ENUM","requiredForDecision":true},
      {"key":"domestic","type":"BOOLEAN","requiredForDecision":true},
      {"key":"incidentDate","type":"DATE","requiredForDecision":true}
    ]$$,
    $$[
      {"actionId":"confirm-held-card-payment","expression":{"op":"AND","conditions":[{"factKey":"cardLost","operator":"EQ","expectedValue":"FALSE"},{"factKey":"unauthorizedPayment","operator":"EQ","expectedValue":"TRUE"},{"factKey":"transactionType","operator":"EQ","expectedValue":"CREDIT_SALE"},{"factKey":"domestic","operator":"EQ","expectedValue":"TRUE"}]}}
    ]$$,
    $$[
      {"actionId":"confirm-held-card-payment","order":1,"title":"미인지 신용판매 확인","description":"카드를 가지고 있는 상태에서 본인이 하지 않은 국내 신용판매를 확인하고 공식 접수 대상인지 확인합니다.","channelRef":"kb-card-compensation-process","evidenceRoles":["PROCEDURE"]}
    ]$$,
    $$[]$$,
    $$[
      {"sourceKey":"kb-personal-card-terms-260402","documentVersion":1,"expectedRawSha256":"fa7261248a6ac0b30ada429f4a37947e6a8b31101db981ad66261c2ca29f3c33","articleReference":"제39조","role":"TERMS"},
      {"sourceKey":"kb-personal-card-terms-260402","documentVersion":1,"expectedRawSha256":"fa7261248a6ac0b30ada429f4a37947e6a8b31101db981ad66261c2ca29f3c33","articleReference":"제41조","role":"TERMS"},
      {"sourceKey":"kb-card-compensation-process","documentVersion":1,"expectedRawSha256":"e1e92c22c4e104e931286e8f44395654f4abd09392d281c36d7d2417a85e4b44","role":"PROCEDURE"}
    ]$$,
    $$[]$$, $$[]$$,
    $$["카드정보 도용·온라인 거래 등 원인은 추측하지 않으며, 미인지 신용판매 확인과 공식 접수 안내만 제공한다.","보상 가능 여부와 책임비율은 확정하지 않는다."]$$,
    'CARD held-card branch is limited to explicit domestic credit-sale facts.', 'human-reviewed-card-breadth', TIMESTAMPTZ '2026-09-19 00:00:00+09'
),
(
    '6226286d-3db1-4608-8132-c0fbd71bb7f8',
    'CARD_COMPENSATION_PROCESS', '㈜KB국민카드', 'PERSONAL_CREDIT_CARD', 1, 'APPROVED',
    DATE '2026-05-12', NULL,
    $$[
      {"key":"institution","type":"INSTITUTION","requiredForDecision":true},
      {"key":"productType","type":"ENUM","requiredForDecision":true},
      {"key":"cardLost","type":"BOOLEAN","requiredForDecision":true},
      {"key":"unauthorizedPayment","type":"BOOLEAN","requiredForDecision":true},
      {"key":"transactionType","type":"ENUM","requiredForDecision":true},
      {"key":"domestic","type":"BOOLEAN","requiredForDecision":true},
      {"key":"reported","type":"BOOLEAN","requiredForDecision":true},
      {"key":"incidentDate","type":"DATE","requiredForDecision":true},
      {"key":"compensationStatus","type":"ENUM","requiredForDecision":true}
    ]$$,
    $$[
      {"actionId":"confirm-compensation-application","expression":{"op":"AND","conditions":[{"factKey":"cardLost","operator":"EQ","expectedValue":"TRUE"},{"factKey":"unauthorizedPayment","operator":"EQ","expectedValue":"TRUE"},{"factKey":"transactionType","operator":"EQ","expectedValue":"CREDIT_SALE"},{"factKey":"domestic","operator":"EQ","expectedValue":"TRUE"},{"factKey":"reported","operator":"EQ","expectedValue":"TRUE"},{"factKey":"compensationStatus","operator":"EQ","expectedValue":"NOT_SUBMITTED"}]}},
      {"actionId":"track-compensation-process","expression":{"op":"AND","conditions":[{"factKey":"cardLost","operator":"EQ","expectedValue":"TRUE"},{"factKey":"unauthorizedPayment","operator":"EQ","expectedValue":"TRUE"},{"factKey":"transactionType","operator":"EQ","expectedValue":"CREDIT_SALE"},{"factKey":"domestic","operator":"EQ","expectedValue":"TRUE"},{"factKey":"reported","operator":"EQ","expectedValue":"TRUE"},{"op":"OR","conditions":[{"factKey":"compensationStatus","operator":"EQ","expectedValue":"SUBMITTED"},{"factKey":"compensationStatus","operator":"EQ","expectedValue":"INVESTIGATING"}]}]}}
    ]$$,
    $$[
      {"actionId":"confirm-compensation-application","order":1,"title":"보상 신청 절차 확인","description":"분실 신고 후 미인지 신용판매에 대한 보상 신청 절차를 KB국민카드 공식 채널에서 확인합니다.","channelRef":"kb-card-compensation-process","evidenceRoles":["PROCEDURE"]},
      {"actionId":"track-compensation-process","order":2,"title":"사고 접수·조사 진행 확인","description":"이미 접수한 사고의 확인·조사 진행 상태와 처리 결과 안내를 공식 채널에서 확인합니다.","channelRef":"kb-card-compensation-process","evidenceRoles":["PROCEDURE","INVESTIGATION"]}
    ]$$,
    $$[]$$,
    $$[
      {"sourceKey":"kb-card-compensation-process","documentVersion":1,"expectedRawSha256":"e1e92c22c4e104e931286e8f44395654f4abd09392d281c36d7d2417a85e4b44","role":"PROCEDURE"},
      {"sourceKey":"crefia-card-loss-compensation-guideline-221021","documentVersion":1,"expectedRawSha256":"bfffe9832f9d3ba7db80e613a120c332ad4e909d2eb8629917dc0cb695011dd6","articleReference":"제4조","role":"GUIDELINE"},
      {"sourceKey":"crefia-card-loss-compensation-guideline-221021","documentVersion":1,"expectedRawSha256":"bfffe9832f9d3ba7db80e613a120c332ad4e909d2eb8629917dc0cb695011dd6","articleReference":"제5조","role":"INVESTIGATION"},
      {"sourceKey":"crefia-card-loss-compensation-guideline-221021","documentVersion":1,"expectedRawSha256":"bfffe9832f9d3ba7db80e613a120c332ad4e909d2eb8629917dc0cb695011dd6","articleReference":"제7조","role":"INVESTIGATION"},
      {"sourceKey":"crefia-card-loss-compensation-guideline-221021","documentVersion":1,"expectedRawSha256":"bfffe9832f9d3ba7db80e613a120c332ad4e909d2eb8629917dc0cb695011dd6","articleReference":"제10조","role":"INVESTIGATION"}
    ]$$,
    $$[]$$, $$[]$$,
    $$["보상신청서 자체의 날짜별 적용성은 별도 확인하지 않으며, 검토된 공식 절차와 조사 진행 확인만 제공한다.","보상 결과·책임·환급 가능성은 확정하지 않는다."]$$,
    'CARD compensation process branch uses reviewed procedure/investigation guidance without asserting outcome.', 'human-reviewed-card-breadth', TIMESTAMPTZ '2026-09-19 00:00:00+09'
),
(
    'f4ed5ef4-4aab-4f29-93c3-1a6557e18f9c',
    'CARD_COMPENSATION_RESULT', '㈜KB국민카드', 'PERSONAL_CREDIT_CARD', 1, 'APPROVED',
    DATE '2026-05-12', NULL,
    $$[
      {"key":"institution","type":"INSTITUTION","requiredForDecision":true},
      {"key":"productType","type":"ENUM","requiredForDecision":true},
      {"key":"cardLost","type":"BOOLEAN","requiredForDecision":true},
      {"key":"unauthorizedPayment","type":"BOOLEAN","requiredForDecision":true},
      {"key":"transactionType","type":"ENUM","requiredForDecision":true},
      {"key":"domestic","type":"BOOLEAN","requiredForDecision":true},
      {"key":"reported","type":"BOOLEAN","requiredForDecision":true},
      {"key":"incidentDate","type":"DATE","requiredForDecision":true},
      {"key":"compensationStatus","type":"ENUM","requiredForDecision":true},
      {"key":"resultDisputed","type":"BOOLEAN","requiredForDecision":true}
    ]$$,
    $$[
      {"actionId":"review-compensation-result","expression":{"op":"AND","conditions":[{"factKey":"compensationStatus","operator":"EQ","expectedValue":"RESULT_RECEIVED"}]}},
      {"actionId":"request-result-review","expression":{"op":"AND","conditions":[{"factKey":"compensationStatus","operator":"EQ","expectedValue":"RESULT_RECEIVED"},{"factKey":"resultDisputed","operator":"EQ","expectedValue":"TRUE"}]}}
    ]$$,
    $$[
      {"actionId":"review-compensation-result","order":1,"title":"처리 결과 확인","description":"이미 받은 사고 처리 결과를 공식 채널에서 다시 확인합니다.","channelRef":"kb-card-compensation-process","evidenceRoles":["PROCEDURE"]},
      {"actionId":"request-result-review","order":2,"title":"결과 재확인 방법 문의","description":"처리 결과에 이견이 있으면 공식 접수 채널에 재확인 방법을 문의합니다.","channelRef":"kb-card-compensation-process","evidenceRoles":["FOLLOW_UP"]}
    ]$$,
    $$[]$$,
    $$[
      {"sourceKey":"kb-card-compensation-process","documentVersion":1,"expectedRawSha256":"e1e92c22c4e104e931286e8f44395654f4abd09392d281c36d7d2417a85e4b44","role":"PROCEDURE"},
      {"sourceKey":"crefia-card-loss-compensation-guideline-221021","documentVersion":1,"expectedRawSha256":"bfffe9832f9d3ba7db80e613a120c332ad4e909d2eb8629917dc0cb695011dd6","articleReference":"제9조","role":"INVESTIGATION"},
      {"sourceKey":"crefia-card-loss-compensation-guideline-221021","documentVersion":1,"expectedRawSha256":"bfffe9832f9d3ba7db80e613a120c332ad4e909d2eb8629917dc0cb695011dd6","articleReference":"제10조","role":"INVESTIGATION"},
      {"sourceKey":"crefia-card-loss-compensation-guideline-221021","documentVersion":1,"expectedRawSha256":"bfffe9832f9d3ba7db80e613a120c332ad4e909d2eb8629917dc0cb695011dd6","articleReference":"제12조","role":"GUIDELINE"}
    ]$$,
    $$[]$$, $$[]$$,
    $$["처리 결과 확인과 재확인 방법 문의만 제공하며, 보상 가능성·책임비율·환급 결과는 판단하지 않는다."]$$,
    'CARD compensation result branch is limited to result review and official follow-up inquiry.', 'human-reviewed-card-breadth', TIMESTAMPTZ '2026-09-19 00:00:00+09'
);
