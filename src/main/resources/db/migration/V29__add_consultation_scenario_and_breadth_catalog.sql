ALTER TABLE consultation ADD COLUMN IF NOT EXISTS scenario VARCHAR(100);

UPDATE consultation
SET scenario = 'CARD_LOSS_UNAUTHORIZED_USE'
WHERE scenario IS NULL AND category = 'CARD';

CREATE INDEX IF NOT EXISTS idx_consultation_scenario
    ON consultation (scenario);

-- Breadth procedures are reviewed definitions, but remain fail-closed until
-- their exact source-document versions are acquired and indexed.  Zero-hash
-- bindings make that missing corpus explicit rather than silently accepting a
-- changed document.
INSERT INTO procedure_version (
    id, scenario, institution, product_type, version, status,
    required_facts_json, condition_rules_json, action_steps_json,
    document_requirements_json, evidence_references_json,
    reviewed_contacts_json, reviewed_values_json, conditions_exceptions_json,
    review_notes, reviewed_by, reviewed_at
)
VALUES
('6aa5b8b5-9c64-4b1e-a70b-1c8a3d8d0c42',
 'VOICE_PHISHING_SUSPICIOUS_TRANSFER', 'GENERIC_FINANCIAL_INSTITUTION', 'BANK_ACCOUNT', 1, 'APPROVED',
 $$[
  {"key":"transferCompleted","type":"BOOLEAN","requiredForDecision":false},
  {"key":"userInitiatedTransfer","type":"BOOLEAN","requiredForDecision":false},
  {"key":"suspiciousTransfer","type":"BOOLEAN","requiredForDecision":true},
  {"key":"institution","type":"INSTITUTION","requiredForDecision":false},
  {"key":"reportedToFinancialInstitution","type":"BOOLEAN","requiredForDecision":false},
  {"key":"policeReported","type":"BOOLEAN","requiredForDecision":false},
  {"key":"maliciousAppInstalled","type":"BOOLEAN","requiredForDecision":false},
  {"key":"authenticationInfoExposed","type":"BOOLEAN","requiredForDecision":false},
  {"key":"incidentDate","type":"DATE","requiredForDecision":false}
 ]$$,
 $$[
  {"actionId":"voice-secure-contact","expression":{"factKey":"suspiciousTransfer","operator":"EQ","expectedValue":"TRUE"}},
  {"actionId":"voice-report-financial","expression":{"op":"AND","conditions":[{"factKey":"transferCompleted","operator":"EQ","expectedValue":"TRUE"},{"factKey":"reportedToFinancialInstitution","operator":"EQ","expectedValue":"FALSE"}]}},
  {"actionId":"voice-report-police","expression":{"op":"AND","conditions":[{"factKey":"transferCompleted","operator":"EQ","expectedValue":"TRUE"},{"factKey":"policeReported","operator":"EQ","expectedValue":"FALSE"}]}}
 ]$$,
 $$[
  {"actionId":"voice-secure-contact","order":1,"title":"이용 중인 금융회사에 안전한 공식 채널로 문의","description":"의심되는 송금 상황을 금융회사의 공식 신고 채널로 확인합니다.","evidenceRoles":["SAFE_GUIDANCE"]},
  {"actionId":"voice-report-financial","order":2,"title":"송금 사실을 금융회사에 신고","description":"이미 송금했다면 거래 정보를 준비해 금융회사에 신고합니다.","evidenceRoles":["PROCEDURE"]},
  {"actionId":"voice-report-police","order":3,"title":"경찰에 신고","description":"피해가 의심되는 송금은 경찰 공식 신고 채널에도 알립니다.","evidenceRoles":["PROCEDURE"]}
 ]$$,
 $$[]$$,
 $$[]$$,
 $$[]$$, $$[]$$,
 $$["금액 환급이나 구제 성공을 보장하지 않는다.","금융회사별 연락처와 기한은 확인된 자료가 있을 때만 표시한다."]$$,
 'VOICE_PHISHING MVP v1 reviewed definition; source corpus activation is required before execution.',
 'human-reviewed-work-7.9.2', TIMESTAMPTZ '2026-09-17 00:00:00+00'),
('6aa5b8b5-9c64-4b1e-a70b-1c8a3d8d0c43',
 'UNAUTHORIZED_ACCOUNT_TRANSFER', 'GENERIC_FINANCIAL_INSTITUTION', 'BANK_ACCOUNT', 1, 'APPROVED',
 $$[
  {"key":"unauthorizedTransaction","type":"BOOLEAN","requiredForDecision":true},
  {"key":"transactionType","type":"ENUM","requiredForDecision":false},
  {"key":"institution","type":"INSTITUTION","requiredForDecision":false},
  {"key":"reported","type":"BOOLEAN","requiredForDecision":false},
  {"key":"accessCredentialExposed","type":"BOOLEAN","requiredForDecision":false},
  {"key":"transactionDate","type":"DATE","requiredForDecision":false}
 ]$$,
 $$[
  {"actionId":"account-transfer-report","expression":{"factKey":"unauthorizedTransaction","operator":"EQ","expectedValue":"TRUE"}},
  {"actionId":"account-transfer-protect","expression":{"factKey":"accessCredentialExposed","operator":"EQ","expectedValue":"TRUE"}}
 ]$$,
 $$[
  {"actionId":"account-transfer-report","order":1,"title":"금융회사에 본인 아닌 거래를 신고","description":"본인이 하지 않은 계좌 거래가 맞는지 확인하고 금융회사의 공식 신고 채널로 문의합니다.","evidenceRoles":["PROCEDURE"]},
  {"actionId":"account-transfer-protect","order":2,"title":"접근수단을 안전하게 보호","description":"인증정보 노출이 의심되면 안전한 공식 채널을 통해 접근수단 보호 방법을 확인합니다.","evidenceRoles":["SAFE_GUIDANCE"]}
 ]$$,
 $$[]$$, $$[]$$, $$[]$$, $$[]$$,
 $$["전자금융거래 책임이나 보상 여부는 거래 사실과 예외 확인 없이는 단정하지 않는다."]$$,
 'UNAUTHORIZED_ACCOUNT_TRANSFER MVP v1 reviewed definition; source corpus activation is required before execution.',
 'human-reviewed-work-7.9.2', TIMESTAMPTZ '2026-09-17 00:00:00+00'),
('6aa5b8b5-9c64-4b1e-a70b-1c8a3d8d0c44',
 'PERSONAL_INFO_SMISHING_MALICIOUS_APP', 'GENERIC_FINANCIAL_INSTITUTION', 'DIGITAL_FINANCIAL_SERVICE', 1, 'APPROVED',
 $$[
  {"key":"suspiciousLinkClicked","type":"BOOLEAN","requiredForDecision":false},
  {"key":"maliciousAppInstalled","type":"BOOLEAN","requiredForDecision":false},
  {"key":"remoteControlUsed","type":"BOOLEAN","requiredForDecision":false},
  {"key":"personalInfoExposed","type":"BOOLEAN","requiredForDecision":false},
  {"key":"authenticationInfoExposed","type":"BOOLEAN","requiredForDecision":false},
  {"key":"moneyMoved","type":"BOOLEAN","requiredForDecision":false},
  {"key":"institution","type":"INSTITUTION","requiredForDecision":false},
  {"key":"incidentDate","type":"DATE","requiredForDecision":false}
 ]$$,
 $$[
  {"actionId":"smishing-stop-contact","expression":{"op":"OR","conditions":[{"factKey":"suspiciousLinkClicked","operator":"EQ","expectedValue":"TRUE"},{"factKey":"maliciousAppInstalled","operator":"EQ","expectedValue":"TRUE"},{"factKey":"remoteControlUsed","operator":"EQ","expectedValue":"TRUE"}]}},
  {"actionId":"smishing-report-financial","expression":{"factKey":"moneyMoved","operator":"EQ","expectedValue":"TRUE"}}
 ]$$,
 $$[
  {"actionId":"smishing-stop-contact","order":1,"title":"의심 링크와 앱 사용을 중단","description":"의심되는 링크나 앱을 더 이용하지 않고 공식 도움 채널을 확인합니다.","evidenceRoles":["SAFE_GUIDANCE"]},
  {"actionId":"smishing-report-financial","order":2,"title":"금융회사에 금전 피해를 신고","description":"돈이 이동했다면 이용 중인 금융회사의 공식 신고 채널로 문의합니다.","evidenceRoles":["PROCEDURE"]}
 ]$$,
 $$[]$$, $$[]$$, $$[]$$, $$[]$$,
 $$["개인정보 노출만으로 금전 피해나 책임을 단정하지 않는다.","앱 격리·삭제 방법은 확인된 공식 자료 범위에서만 안내한다."]$$,
 'PERSONAL_INFO_SMISHING_MALICIOUS_APP MVP v1 reviewed definition; source corpus activation is required before execution.',
 'human-reviewed-work-7.9.2', TIMESTAMPTZ '2026-09-17 00:00:00+00')
ON CONFLICT (scenario, institution, product_type, version) DO NOTHING;
