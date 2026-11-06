# 공식 정보 Coverage 평가 계약

이 질문은 평가 계획이다. `UNVERIFIED`는 공식 원문 정답·적용 범위·운영 Corpus를 아직 확인하지 않았다는 뜻이다. 합성 `FOUND`는 실제 금융정보 Coverage가 아니다. Fixture의 기관·문서·날짜는 테스트 범위를 설명하며 운영 승인 데이터가 아니다.

## 정답 확정 Gate

질문별로 검토자가 적용 사실, 공식 문서 ID/판본/Raw Hash, SUPPORT 및 필요한 CONDITION/EXCEPTION Chunk와 Locator, 적용 기관·상품·사건일, 기대 상태를 원문과 대조한 뒤 별도 승인해야 한다. 승인 전에는 운영 Coverage 지표의 분모에 넣지 않는다. 부정 예시도 같은 방식으로 왜 부적합한지 기록한다. 모델 출력이나 같은 Fixture를 정답 판정자로 사용하지 않는다.

## 지표와 집계

질문을 동등하게 1건씩 집계한다. 시나리오별 값과 전체 값을 함께 내고, 표본 수와 K를 표시한다.

| 지표 | 분자 / 분모 |
|---|---|
| Answerable Coverage | 검증된 정답 문서·필수 조건·예외가 현재 승인 Corpus의 후보에 모두 있는 질문 / 사람이 정답을 확정한 대상 질문 |
| Evidence Recall@K | 상위 K 안에 포함된 필수 정답 Evidence ID 수 / 필수 정답 Evidence ID 수. 질문별 값 평균과 누락 질문 수를 함께 표시 |
| Precision@K | 상위 K 후보 중 사람이 관련성을 판정한 적합 후보 수 / 판정 가능한 상위 K 후보 수. 미판정 후보는 성공으로 세지 않음 |
| 범위 오적용률 | 부적합 기관·상품·사건일 후보가 노출된 질문 수 / 범위가 검증된 질문 수 |
| 조건·예외 누락률 | 필수 조건·예외 Locator가 빠진 반환 후보 수 / 조건·예외가 필수인 판정 가능한 반환 후보 수 |
| 잘못된 FOUND 비율 | 정답 불가 또는 부적합으로 확정된 질문에 FOUND가 반환된 수 / 정답 불가·부적합으로 확정된 질문 수 |
| 잘못된 차단 비율 | 정답 가능으로 확정된 질문이 NEEDS_CLARIFICATION·NO_MATCH·STALE 등으로 끝난 수 / 정답 가능으로 확정된 질문 수 |

분모가 0이면 `N/A`, 운영 Corpus·원문 정답·검색 결과를 확보하지 못하면 `UNVERIFIED`로 기록한다. 검색 장애는 `NO_MATCH`로 재분류하지 않고 별도 집계한다. Stale Revision 차단은 유효한 안전 차단과 잘못된 차단을 구분해 판단한다.

KURE, PostgreSQL Keyword, RRF는 **같은 질문·Confirmed Facts·승인 Corpus 판본·READY Generation·K**에서 각각의 상위 K와 장애율을 비교한다. 검색 결과의 적합성 판정은 독립된 원문 검토 기록에 따른다. Parser의 본문·표·Locator 품질은 검색 순위와 별도 평가한다.

현재 데이터셋 16건의 실제 Corpus 지표는 모두 `UNVERIFIED`다. `GoldenCoverageEvaluationTest`의 합성 Probe는 검색 상태와 허용 ID 경계만 검증한다. V38의 DB 제약·승인 상태는 별도 격리 DB 통합 테스트의 책임이다.

DB의 합성 자격 테스트는 단일 합성 Chunk에 SUPPORT·CONDITION·EXCEPTION 역할을 함께 붙여 관계의 존재와 차단을 확인한다. 이는 **서로 다른 원문 조항의 조건·예외 의미를 검증한 사례가 아니다.** 실제 Coverage 평가에서는 각 역할의 원문 조항과 Locator를 독립적으로 판정해야 한다.
