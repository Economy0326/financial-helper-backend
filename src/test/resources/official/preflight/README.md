# 공식 Source 원문 Preflight

`OfficialSourcePreflightTest`는 V10/V17에 선언된 HTML Source Key 다섯 개만 점검한다. URL 인자를 받지 않으며 기존 `HttpSourceContentFetcher`, `SourceUrlPolicy`, `HtmlSourceDocumentParser`를 사용한다. DB, SourceDocumentWriter, V38 Review, READY Generation은 호출하지 않는다. 원문 bytes는 프로세스 메모리에서만 사용하고 결과에는 본문·Chunk 본문을 기록하지 않는다.

기본 오프라인 테스트는 allowlist와 선언 일치, 임의 Key 차단, 원문 조회 실패, 짧은 HTML 실패, 합성 bytes Hash와 Offset을 검증한다. 실제 원문 조회는 명시적으로 한 테스트만 선택하고 프로세스 환경변수 `OFFICIAL_PREFLIGHT_LIVE=true`를 넣을 때 실행된다.

```powershell
$env:OFFICIAL_PREFLIGHT_LIVE = 'true'
try {
  .\gradlew.bat test --offline --no-daemon --console=plain `
    --tests 'com.financialhelper.source.OfficialSourcePreflightTest.liveOfficialUrlsProduceReviewJsonOnlyWhenExplicitlyRequested' `
    --rerun-tasks
} finally {
  Remove-Item Env:OFFICIAL_PREFLIGHT_LIVE -ErrorAction SilentlyContinue
}
```

검토 JSON은 JUnit XML의 system-out에 기록된다. `access`, `parser`, `locator`, `failureCode`를 Source별로 읽는다. Gradle 성공은 각 Source의 접근 성공을 뜻하지 않는다. HTTP fetcher의 성공은 HTTP 2xx 응답으로만 발생하지만 현재 결과 DTO에 정확한 HTTP Status는 없다. 실조회 시각과 최종 URL을 함께 비교한다. 원문이 자주 바뀌므로 Hash는 해당 시각에 확보한 bytes의 신원일 뿐 Repository의 승인 판본과 일치한다는 증거가 아니다.

Chunk 수와 Offset은 테스트용 로컬 whitespace tokenizer로 산출한다. 실제 KURE tokenizer·인덱스 Chunk 수를 대표하지 않는다. `locator=PASS`는 정규화 본문과 테스트 Chunk의 UTF-16 substring 일치만 의미한다. 공식 문서의 표·조건·예외의 의미 보존, 조항 정확도, 문서 시행 기간은 별도 Human Review가 필요하다. `v38Readiness=ADDITIONAL_REVIEW_REQUIRED`는 자동으로 변경되지 않는다.

이 Preflight는 기존 Law API의 사건일별 판본 선택을 실행하지 않는다. ACCOUNT 전자금융거래법과 VOICE 특별법은 `KoreanLawOpenApiClient`의 별도 경로에서 시행일·조항·예외와 기존 Source Corpus 중복을 검토해야 한다. 임의 법령 검색이나 법률적 적용 승인으로 확대하지 않는다.
