# 정보형 자연어 상담 API

기존 상담 생성 `POST /api/v1/consultations`으로 Consultation과 Guest 쿠키를 먼저 확보한다. 계정 인증이 필요한 환경에서는 기존 인증 정책을 따른다. 아래 변경 요청은 기존 CSRF 쿠키·`X-XSRF-TOKEN` 헤더가 필요하며 모든 조회·수정은 해당 상담의 소유권을 검사한다.

1. `GET /api/v1/consultations/{id}/information-intake`에서 현재 `caseInputRevision`과 입력을 확인한다. 명시적 기존 카테고리 상담에는 사용하지 않는다.
2. `PUT /api/v1/consultations/{id}/information-intake`에 `{"situationText":"카드를 잃어버렸어요","expectedCaseInputRevision":0}`을 보낸다. Revision은 예시이며 직전 GET 결과를 사용한다.
3. 응답은 `consultationId`, 새 `caseInputRevision`, `status`, 원래 `situationText`, `scenarioCandidates`, `confirmedScenario`를 포함한다. `SCENARIO_CONFIRMATION_REQUIRED`는 **후보 하나에 대한 사용자 확인 대기**이고 `NEEDS_CLARIFICATION`은 후보가 없거나 복수인 경우다. 기존 CARD 제품 제한에 명시적으로 해당하면 `UNSUPPORTED_SCOPE`다. 후보는 Confirmed Fact나 공식 근거가 아니다. 추가 설명은 새 Revision으로 같은 PUT에 전체 수정 문장을 보내면 된다.
4. 후보가 하나이고 사용자가 확인한 경우 `POST /api/v1/consultations/{id}/information-intake/confirm`에 `{"scenario":"CARD_LOSS_UNAUTHORIZED_USE","expectedCaseInputRevision":1}`을 보낸다. 성공하면 `READY`, `confirmedScenario`와 새 Revision이 반환된다. 복수 후보는 명시적 구분을 위한 수정이 먼저 필요하다.
5. 기존 Follow-up을 통해 기관·상품·날짜 등 필요한 사실을 확인한 뒤 `POST /api/v1/consultations/{id}/information-report/prepare`에 최신 `expectedCaseInputRevision`을 보낸다. 저장된 결과는 `GET /api/v1/consultations/{id}/information-report`로 재조회한다. 공식 근거가 없으면 부족 상태이며, Revision·근거 변경 시 STALE을 처리해야 한다.

사건 유형 판별은 보수적인 문자열 신호를 사용하는 후보 분류다. 부정·추측·복합 사건 등 모든 표현의 의미를 이해한다고 보장하지 않으며, 분류만으로 금융기관·상품·피해·법적 결과를 확정하지 않는다. 입력 수정 시 기존 선택과 Follow-up Revision이 무효화되므로 확인 절차를 다시 거친다. 기존 명시적 카테고리 경로와 충돌하지 않도록 자연어 진입 상담의 상황 수정은 이 API를 사용한다.

정보형 Report의 `evidenceProvenance`는 선택된 SUPPORT Chunk ID별 상태다. 현재 검증 가능한 별도 출처 신원 저장 계약이 없어 모두 `UNKNOWN`이며 `.invalid`, URL·발행기관·V38 Review만으로 실제/합성 여부를 결정하지 않는다. Source 무결성, V38 범위 검토, 사건 적용 여부는 기존 Snapshot의 별도 상태를 사용한다. 합성 여부를 명시하려면 별도 검증 가능한 신원 계약이 필요하다.

누락·빈 입력은 HTTP 400, CSRF 실패는 403, 세션 누락·만료는 401, 소유하지 않은 상담은 404, Revision 불일치·부적절한 상태·불일치 확인은 409다. 검색 장애·정보 부족은 기존 Report 상태를 확인한다. 예시 본문에 개인정보나 인증정보를 입력하지 않는다.

Frontend는 기존 카테고리 선택을 강제하기 전에 이 화면을 호출하고 후보 확인·추가 입력 상태를 표시해야 한다. 카테고리 선택을 백그라운드에서 추정하거나 Revision을 임의 생성해서는 안 된다. 이전 ACTION API와 Emergency 경로는 별도로 유지한다.
