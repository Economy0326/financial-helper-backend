# CARD 공통정보 원문 검토 후보 — 2026-11-06

상태: **검토 준비 / NOT_APPROVED**. 이 파일은 공식 문서 승인, V38 Review 또는 실제 검색 Coverage가 아니다. Source Key·Document ID·Chunk ID·적용 시작일·검토자 결정을 만들지 않았다. 아래 원문 관찰은 웹 도구가 표시한 본문에 한하며 원본 bytes나 프로젝트 Parser 출력을 확보한 결과가 아니다.

## 1. 여신금융협회 신용카드 이용자 가이드

- 요청 URL: https://customer.crefia.or.kr/common/forward.xx?url=%2Fcustomer%2Fguard%2FguardCreditcardUseGuide6
- 2026-11-06 확인: 시간 초과로 본문 미열람. Redirect, 제목, 문장, 카드 종류, 조건과 예외, 게시일·시행일, bytes Hash, Parser·Chunk·Locator 모두 **UNVERIFIED**.
- SUPPORT / CONDITION / EXCEPTION: **NOT_REVIEWED**. COMMON 승인 또는 등록 대상으로 간주하지 않는다.

## 2. 여신금융협회 카드 분실일괄신고서비스

- 요청 URL: https://customer.crefia.or.kr/common/forward.xx?url=%2Fcustomer%2Fguard%2FguardCreditcardLoss
- 웹 도구가 표시한 최종 URL: https://customer.crefia.or.kr/common/forward.xx?url=/customer/guard/guardCreditcardLoss
- 웹 도구는 확인 당시 이 페이지의 수집 시점을 2일 전으로 표시했다. 현재 서버의 원본 bytes가 동일하다고 확인한 것은 아니다.
- 표시 제목: `카드 분실일괄신고서비스`. 발행기관: 여신금융협회 소비자지원센터. 확인 시각: 2026-11-06 18:07 KST 무렵. 웹 도구가 표시한 HTML 본문이며 최초 응답의 bytes 및 HTTP redirect chain은 확보하지 못했다.
- 게시·개정·시행·적용 기간: **UNVERIFIED**. Raw SHA-256, 본문 fingerprint, SourceDocument version, 추출 offset·Chunk·Locator: **UNVERIFIED**. 문서 사용·보존 조건도 **UNVERIFIED**. 원문 전문은 이 저장소에 복제하지 않는다.

### 사람이 대조할 SUPPORT 후보와 필수 문맥

| 원문상 위치 | 요약 | 검토할 조건과 예외 |
| --- | --- | --- |
| `1. 카드 분실일괄신고서비스란?` | 분실한 카드의 금융회사 중 한 곳을 통해 다른 금융회사의 분실 카드도 일괄 신고할 수 있다는 설명 | 분실 상황, 참여회사, 신고인이 선택한 분실 카드의 범위. 모든 카드가 자동으로 신고된다는 뜻인지 추가 확인 필요 |
| `2. 이용 방법` 대상카드 | 신고인 본인명의 신용·체크·가족카드 | 본인명의 판단 조건과 가족카드의 실제 적용 관계 확인. 법인카드는 명시적으로 제외 |
| `2. 이용 방법` 신고 절차 1~3 | 접수 카드사 신고, 다른 분실 카드사 선택·요청, 수신 카드사 접수 확인 문자 순서 | 참여회사·분실 카드사 조건과 각 카드사별 실제 접수 여부를 함께 확인 |
| `1.` 주의사항 / `2.` 마지막 항목 | 전체 분실 신고 시 자동이체 카드 사용에 영향을 줄 수 있고, 접수에 식별정보 제공 및 정보 이용·제공 동의가 필요하다는 안내 | 이 서비스의 입력란에 개인정보를 받거나 이 문구를 사건별 의무로 단정하지 않음. 실제 절차와 최신 조건을 사람 검토 |

참여회사 수·목록, 신고 채널과 연락처는 변경 가능하므로 문서 판본과 검토 시점 없이 고정하지 않는다. 웹 본문에는 참여회사 목록과 접수처 표 사이에 표현 차이가 있어 상세 일치 여부를 추가 확인해야 한다. 카드 분실 안내로 보상·환급 결과, 책임 비율, 모든 카드사 약관의 적용을 추론하지 않는다. 도난 사건·체크·가족카드의 세부 적용과 유효 시점은 별도 확인 전 확정하지 않는다.

## 3. Golden 질문과 현재 구현 범위

- `CARD-01`: 일괄신고 일반 안내의 원문 후보는 있으나 실제 Parser·기간·V38·READY 검색은 미검증이다.
- `CARD-02`: 이 공통 후보는 KB 등 특정 기관의 약관을 대신하지 않는다.
- `CARD-03`: 원문은 일괄신고 대상에 체크카드를 열거하지만, 현재 정보 후보 서비스의 CARD 제품 분기는 개인 신용카드만 지원한다. 체크카드 검색을 활성화하거나 약관 적용성을 추론하지 않는다.
- `CARD-04`: 보상 여부와 카드 보유 상태의 법적 결과에 대한 근거로 사용하지 않는다.

## 4. 사람 검토 후 로컬 활성화에 필요한 순서

1. 별도 승인된 로컬 환경에서 공식 URL·redirect·원본 bytes·SHA-256과 이용 조건을 확인하고 원문 판본 및 적용기간을 기록한다. 위 HTML 표현만으로 기존 SourceDocument Hash를 추정하지 않는다.
2. 실제 공식 도메인 allowlist와 SourceRegistry 후보를 검토한 후 기존 `SourceIngestionService`의 명시적 수집 경로를 사용한다. `SourceIngestionRunner`의 전체 enabled Source 일괄 수집은 의도하지 않은 추가 수집이므로 자동 실행하지 않는다.
3. `HtmlSourceDocumentParser`와 `SourceChunkingService` 결과를 원문과 대조한다. 조건·예외·표·Locator·UTF-16 offset 및 변경된 제목을 수동 검토한다. 본문 fingerprint는 Raw Hash 불일치를 승인하지 않는다.
4. 검토자가 정확한 Document version·Raw Hash와 SUPPORT / CONDITION / EXCEPTION 연결, Scenario·상품·기관·사건일·시작일 근거를 확인하고 별도로 결정한다. 이 저장소의 현재 코드에서 `ReviewedSourceApplicabilityService`는 승인된 Review를 **조회**한다. 사람 승인용 관리 UI 또는 안전한 운영 명령의 존재는 확인되지 않았다.
5. 승인된 Document·Chunk만 기존 Retrieval Generation에 인덱싱하고 READY 전환을 별도로 확인한다. 정보형 검색·Report를 테스트 데이터와 실제 문서로 각각 검증한다. 실제 승인 전에는 활성화하지 않는다.

실제 공식자료를 승인·등록·인덱싱한 작업은 수행하지 않았다. VOICE / ACCOUNT / SMISHING의 운영 DB와 현재 승인 Corpus는 이번 조사에서 조회하지 않아 **UNVERIFIED**다.

## 5. 합성 검색 데모 재현

Backend 루트에서 별도 PowerShell 프로세스로 실행한다. Docker Linux Engine과 이미 로컬에 있는 `postgres:16-alpine` 이미지가 필요하다. 이미지를 다운로드하지 않으며 기존 DB·컨테이너를 사용하지 않는다. 스크립트는 일회성 컨테이너의 ID·이름·라벨·Loopback·tmpfs를 검사하고 완료 후 일치하는 대상만 제거한다. Spring 테스트의 `IsolatedDatabaseInitializer`도 JDBC 대상을 확인한다.

```powershell
powershell -NoProfile -File scripts/local/run-synthetic-card-evidence-test.ps1
```

대상은 가상의 `synthetic.invalid` Source와 테스트 전용 승인 데이터다. KURE·OpenAI·법령은 Mock이며, 검증된 Corpus 운영 성과나 실제 협회 문서 승인이 아니다. 정보형 HTTP Report 생성·조회 후 Review 철회에 따른 STALE과 16개 Golden 질문의 합성 평가를 실행한다.
