# 금융도우미 Backend

금융 피해 상담에서 사용자 사실을 구조화하고,
검토된 공식 근거와 승인된 절차를 기반으로 다음 행동을 결정하는 Spring Boot Backend입니다.

AI가 금융 행동을 자유롭게 생성하지 않도록
상담 상태, Fact, Evidence, Procedure, Action Plan을 Backend가 관리합니다.

## Architecture

```text
Next.js
  ↓
Spring Boot
  ↓
PostgreSQL

Spring Boot
├─ OpenAI
├─ KURE-v2 Retrieval Runtime
└─ Korean Law Open API
```

## Grounded Consultation

```text
User Input
→ ConfirmedCaseSnapshot
→ Adaptive Follow-up
→ Approved Evidence Retrieval
→ ProcedureVersion
→ FinancialActionPlan
→ AnalysisEvidenceSnapshot
→ AI Explanation
→ Backend Validation
→ Stored Report
```

Backend가 금융 행동의 **WHAT**을 결정하고,
AI는 승인된 행동과 근거를 사용자가 이해하기 쉬운 문장으로 설명합니다.

행동, 기한, 금액, 연락처, 필요 서류, 법 적용을
AI가 임의로 만들어낼 수 없습니다.

## Confirmed Facts

사용자가 명시적으로 확인한 사실만 `ConfirmedCaseSnapshot`에 저장합니다.

`UNKNOWN`도 정상적인 domain value로 유지하며,
같은 핵심 사실을 반복해서 묻거나 AI 추론으로 값을 채우지 않습니다.

Situation과 Follow-up이 변경되면 revision을 갱신하고,
이전 revision의 Summary / Analysis / Report를 현재 결과로 재사용하지 않습니다.

## Evidence / Retrieval

공식 자료는 바로 검색에 사용하지 않습니다.

```text
SourceRegistry
→ SourceDocument / Version / Hash
→ Human Review
→ APPROVED
→ SourceChunk
→ Retrieval Generation
→ READY
```

Retrieval은 다음 결과를 결합합니다.

- KURE-v2 semantic retrieval
- PostgreSQL keyword retrieval
- Reciprocal Rank Fusion
- Parent / Condition / Exception expansion

현재 frozen generation:

```text
mvp-breadth-cc6cf90a40c3b013
```

READY chunks: `117`

## Law Evidence

필요한 법령은 법제처 국가법령정보센터 Open API를 Backend에서 직접 조회합니다.

사건 기준일과 법령 시행일을 비교해 검토된 version만 사용하며,
법령 identity나 적용 시점을 확인할 수 없으면 fail closed 처리합니다.

## Supported Scope

- KB국민카드 개인 본인 신용카드의 국내 분실·도난 / 본인 아닌 결제
- 보이스피싱 / 의심 송금
- 본인이 하지 않은 계좌이체·출금
- 스미싱 / 개인정보 노출 / 악성·원격제어 앱
- Public / Guest Emergency Response

공식 Procedure나 Evidence가 없는 범위는
다른 Scenario의 근거를 섞지 않고 unsupported / coverage gap으로 처리합니다.

## Authentication / Security

- Kakao / Naver Social Login
- `(provider, providerSubject)` 기반 AccountIdentity
- Backend HttpOnly service session
- CSRF / CORS
- Account ownership 검증
- Account당 ACTIVE Consultation 최대 1개
- 최근 7일 새 일반 상담 최대 5회
- request / rate / AI attempt guard
- 상담 원문과 secret 로그 제한

Emergency는 일반 상담 인증과 분리된 Public / Guest flow입니다.

## Tech Stack

`Java 21` `Spring Boot` `Spring Security`  
`PostgreSQL` `Flyway` `JPA`  
`OpenAI Structured Output`  
`KURE-v2` `Korean Law Open API`

## Run

필수 환경변수 예:

```text
DB_URL
DB_USERNAME
DB_PASSWORD
OPENAI_API_KEY
LAW_OC
KURE_RUNTIME_ENDPOINT
```

```bash
gradlew.bat bootRun
```

KURE local runtime:

```bash
py -m retrieval_runtime.runtime --host 127.0.0.1 --port 8091
```

## Verification

```bash
gradlew.bat clean test --no-daemon
gradlew.bat clean build --no-daemon
```

CARD와 지원 Scenario는
PostgreSQL → Retrieval → Procedure → Law Evidence → OpenAI → Stored Report까지
grounded E2E로 검증했습니다.

## Links

- [Frontend Repository](https://github.com/Economy0326/financial-helper-frontend)
- [Project Documentation](https://cute-quit-4fd.notion.site/3bd25931ce3580ae8ad8f0fa3acdf41a)