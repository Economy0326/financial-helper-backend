# R4-B 합성 검색 평가 — 2026-11-17

**상태: PARTIAL.** 독립 작성한 잠정 정답을 합성 문서 A/B의 승인 Claim 두 개에 적용했다. 실제 사용자의 정답이나 금융제도 정확성을 검증한 평가가 아니다. 본문·조건·예외는 자체 제작 합성 자료다. 표 Claim은 포함하지 않았다.

격리 PostgreSQL 키워드는 기존 제품 서비스와 승인·READY 멤버십을 사용했다. 실제 KURE-v2는 동일한 두 Chunk ID와 본문을 별도의 임시 PLAID Generation에서 사용했다. RRF는 제품의 `k=60` 공식을 오프라인으로 계산했다. **제품 활성 Generation에서 실제 KURE와 RRF를 함께 실행한 평가는 아니다.** 평가 당시 두 합성 문서의 예외가 유사하고, 서로 다른 조건을 가진 충분한 corpus가 없었다.

| 사례 | 질문 | 정답 | 키워드 순위 | KURE 순위 | 오프라인 RRF 순위 |
|---|---|---|---|---|---|
| 원문 일치 A | 예금은 | A | A | A, B | A, B |
| 원문 일치 B | 신규 | B | B | B, A | B, A |
| 동의어 A | 저축 | A | 없음 | A, B | A, B |
| 동의어 B | 융자 | B | 없음 | B, A | B, A |
| 조사 변화 | 예금 | A | 없음 | A, B | A, B |
| 예외 | 신규 기존 계약은 제외합니다 | B | B | A, B | B, A |
| 연도 | 2025년 | A, B | B, A | B, A | B, A |
| 명확화 검색 | 신규 신청할 수 있습니다 | B | B | B, A | B, A |
| Follow-up 검색문 | 신규 기존 계약은 제외합니다 | B | B | A, B | B, A |
| 상품 전환 검색문 | 예금은 | A | A | A, B | A, B |
| 근거 없음 | 해외주식환전수수료 | 없음 | 없음 | B, A | B, A |

| 방식 | Recall@3 | Precision@3 | NDCG@3 |
|---|---:|---:|---:|
| PostgreSQL 키워드 | 0.7000 | 0.2667 | 0.7000 |
| 실제 KURE, 임시 인덱스 | 1.0000 | 0.3667 | 0.9262 |
| 오프라인 RRF | 1.0000 | 0.3667 | 1.0000 |

정답이 있는 10건의 macro 평균이며 Precision@3의 분모는 항상 3이다. 무근거 질문은 평균에서 제외하고 별도 표시했다. 키워드는 의미 분기가 없는 테스트 대역 환경에서 미검색 시 `TEMPORARILY_UNAVAILABLE`을 반환했다. KURE와 RRF는 무근거 질문에도 두 결과를 반환해 관련성 검증이 필요하다. 두 Claim만 존재하므로 Recall@3은 특히 낙관적이다. 위 명확화·Follow-up 행은 검색문 평가이며 대화 전체의 새로운 평가가 아니다. 철회·미승인 차단은 별도의 기존 격리 통합 회귀로 확인했다.

모델은 `nlpai-lab/KURE-v2`, revision `3431f86d399d666083890dbb882aced6708873bc`, CPU, PyLate 1.6.0, fast-plaid 1.4.6.2110이다. 외부 AI는 호출하지 않았다. 현재 Report는 원문 기반 결정적 설명이며 AI 생성·검증·실패 시 fallback 전체 경로는 미구현이다. 문서별 EXTERNAL_AI 권리는 별개다.

재현: 로컬 고정 모델 가중치와 격리 PostgreSQL 환경에서 `$env:FH_R4B_KEYWORD_EVAL='true'; pwsh -NoProfile -File scripts/local/run-synthetic-card-evidence-test.ps1 -R3UI`를 실행한다. 이 명령은 합성 Claim과 결과를 `build/reports/r4b-synthetic-corpus.json`, `r4b-keyword-evaluation.md`에 생성한다. 이어 `python -m scripts.evaluation.r4b_synthetic_kure`를 실행하면 오프라인 임시 인덱스와 `build/reports/r4b-kure-rrf-evaluation.md`를 생성한다. 테스트 도중 생성한 DB·인덱스만 사용하며 기존 시연 자원은 사용하지 않는다.
