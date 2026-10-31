# 원문 추출 품질 기준선

이 폴더의 HTML은 테스트 작성자가 만든 합성 자료다. KB 원문 전체·부분의 저장본이 아니며 문서 판본, 실제 서류 요건, Human Review 또는 Answerable Coverage 정답이 아니다. 기존 coverage 질문 16건의 UNVERIFIED 상태를 변경하지 않는다.

## 검증 범위와 한계

- `SourceExtractionGoldenTest`: 합성 HTML의 개인/기업 순서, 카드대출·위변조·대리 접수 조건, 접수/조사/통지 단계, 정규화 텍스트와 Chunk UTF-16 offset의 일치를 확인한다. 셀 의미의 구조화된 모델이나 DOM 원문 offset을 보장하지 않는다.
- PDF는 기존 PDFBox 방식으로 메모리에서 생성한다. 두 페이지와 구분자를 가진 텍스트 행, 조건·예외 순서를 검증한다. 실제 표의 좌표·병합 셀·다단 읽기 순서·OCR·한글 글꼴은 미검증이다.
- HWP는 기존 Apache POI로 최소 OLE/Section/PARA_TEXT 레코드를 메모리에서 생성한다. 문단·조항·순서를 검증하지만 완전한 한글 렌더링 문서나 표 셀 구조 Fixture가 아니다. 기존 공식 HWP 테스트의 Hash/키워드 통과도 조항 의미 승인과 다르다.
- 별도 Chunk의 조건/예외는 동일 articleReference와 offset으로 사람이 찾을 수 있다. 자동 V38 관계 생성이나 완전한 예외 추적을 보장하지 않는다.
- 토큰 계산은 로컬 whitespace 구현, persistence는 Mock이다. Spring/DB/외부 KURE/AI/법령/OAuth를 사용하지 않는다.
- `SourceExtractionQualityGapTest`는 예상되는 안전 불변식을 검사하는 결함 재현 테스트다. 실패를 숨기기 위해 disable하거나 결함 동작을 정답으로 바꾸지 않는다.

## 수정 전 재현 기록

기준 HEAD `aaad98cc20976cc43927855575483a92b6159080`에서 기존 Parser/Chunking 8건과 신규 6건을 실행했다. JUnit XML 기준 14건 중 12 PASS, 2 FAIL, errors/skipped 0, Gradle 종료 코드 1이었다. 프로덕션 코드는 수정하지 않았다.

- `oversizedFirstLineMustNotDropFollowingException`: 수정 전 SourceChunkingService.split의 첫 줄 초과 경로가 즉시 반환하여 뒤 예외 문장이 누락됐다.
- `hwpSectionTenMustFollowSectionNineInsteadOfSectionOne`: 수정 전 HwpSourceDocumentParser.sections가 문자열 정렬을 사용하여 Section10이 Section2보다 먼저 나왔다.
- `conditionAndExceptionTextPreserved=true` metadata는 현재 고정 값이므로 예외 보존의 독립적인 증거로 사용할 수 없다.

## 두 결함 수정 후 검증

긴 첫 줄의 분할 결과 중 마지막 조각을 보류한 뒤 기존 후속 줄 처리로 이어지도록 수정했다. 정상 첫 줄의 분할 경로와 UTF-16 Locator 형식은 유지한다. HWP는 Section 번호의 숫자 순으로 정렬하고 Section0부터 연속된 정규 이름인지 확인한다. Section 접두사의 잘못된 이름, 숫자 overflow, 번호 누락, 숫자 중복 별칭(Section00), 선행 0(Section01)은 명시적 parse 실패로 처리한다. 비정상 입력을 조용히 무시하던 일부 경우도 거절하는 방향으로 강화했다.

기존 실패 재현 2건을 먼저 실행하여 2 PASS 및 Gradle 종료 코드 0을 확인했다. 이어 아래 전체 명령을 실행하여 JUnit XML 기준 33 PASS, failures/errors/skipped 0, 최종 Gradle 종료 코드 0을 확인했다. 합성 HWP 11개 Section의 본문 및 Chunk 순서, 예외 이후 마지막 문장까지의 누락/중복 없음, UTF-16 offset과 emoji 경계, 잘못된 Section 이름 7개 사례를 포함한다. 기존 실제 HWP 파일의 Hash/키워드 검증도 통과했다. 공식 원문의 의미 검토나 실제 Corpus Coverage 성과는 아니다.

실행 명령 (DB·외부 runtime 없이 기존 Gradle 캐시 사용):

```powershell
.\gradlew.bat test --offline --no-daemon --console=plain `
  --tests com.financialhelper.source.HtmlSourceDocumentParserTest `
  --tests com.financialhelper.source.PdfSourceDocumentParserTest `
  --tests com.financialhelper.source.HwpSourceDocumentParserTest `
  --tests com.financialhelper.source.SourceChunkingServiceTest `
  --tests com.financialhelper.source.SourceExtractionGoldenTest `
  --tests com.financialhelper.source.SourceExtractionQualityGapTest `
  --tests com.financialhelper.retrieval.OfficialSourceRetrievalServiceTest
```

## 기존 Golden 질문 연결

| 질문 | 이 단계가 제공하는 검증 기반 | 남은 원문/실행 확인 |
|---|---|---|
| CARD-01 | 조항/조건 분리 및 Locator | 협회 공통 조항, 상품·시점 |
| CARD-02 | 개인/기업 등 범위 문맥 보존 | 기관 확인·실제 약관 판본 |
| CARD-03 | 상품 조건 텍스트 보존 | 체크카드 직접 근거 |
| CARD-04 | 예외·접수/결과 구분 | 사건일·카드 보유 Fact, 보상 결과 확정 금지 |
| VOICE-01 | 신고 절차의 조건 보존 기반 | 일반 송금사기 공식 절차 |
| VOICE-02 | 직접 검증 없음 | Scenario 차용 차단은 기존 검색 테스트 책임 |
| VOICE-03 | 조건이 별도 Chunk로 분리될 때 식별 | 법정 대상·예외·시점 원문 |
| VOICE-04 | 직접 검증 없음 | 검색 장애 테스트 책임 |
| ACCOUNT-01 | 조항/조건 기반 | 무단이체 직접 제도 자료 |
| ACCOUNT-02 | 범위 문맥 보존 기반 | 기관별 문서와 검색 필터 |
| ACCOUNT-03 | 원본/정규화 Hash 보존 | 거래일 당시 판본과 적용 기간 |
| ACCOUNT-04 | 직접 검증 없음 | 사용자 정정·Revision 테스트 책임 |
| SMISHING-01 | 절차·조건 순서 기반 | 의심 메시지·노출 예방 범위 |
| SMISHING-02 | 직접 검증 없음 | Review 철회 테스트 책임 |
| SMISHING-03 | 직접 검증 없음 | 악성앱 직접 원문·Scenario 범위 |
| SMISHING-04 | 조건·예외 분리 기반 | 설치/노출/금전 피해 Facts와 각 직접 근거 |

## 공식자료 확보 우선순위 (수집·승인 지시가 아님)

1. ACCOUNT: 무단이체와 관련된 공식 전자금융거래 제도, 기관별 신고/조사 절차, 거래일 당시 판본. 특정 문서 제목·URL·시행일은 아직 확정하지 않는다. 기관 선택과 원문 제공 경로 확인을 선행한다.
2. VOICE: 피해 신고/지급정지/피해구제 각각의 공식 원문과 법정 대상·예외·시점. Repository의 경찰청 제보 안내는 구제 절차와 구분한다. S6 일반 대응 문단을 특정 기업 경보·연락처와 분리하여 검토한다.
3. CARD: 협회 지침 로컬 HWP를 조항 단위로 직접 대조하고, KB 약관/신청서의 동일 판본을 확보한다. 체크카드·KB 외 공통 범위는 별도로 확인한다.
4. SMISHING: 의심 메시지, 앱 설치, 개인정보 노출, 실제 금융 피해, 상담/신고를 별개 사실·문서 범위로 검토한다. S7 예방 안내와 KISA 상담 범위를 금전 피해 구제 근거로 확대하지 않는다.

2단계에서 열람한 후보의 출처 경로(이번 단계 재조회·다운로드 안 함):
- KB 처리절차: https://mapps.kbcard.com/SVC/DVIEW/HSGMCXCRSCSC0004
- 경찰청 제보: https://www.counterscam112.go.kr/board/CONTENT_000000000010.do
- FSC S6: https://www.fsc.go.kr/no010101/86271
- FSC S7: https://www.fsc.go.kr/edu/cardnews?cnId=1527
- KISA: https://www.kisa.kr/118

## V38 승인과 실제 검색 평가의 선행조건

원본의 보존 권한/이용 조건 → 공식 URL·원본 bytes·Raw Hash·판본 → 독립적으로 사람이 확인한 조항·표·조건·예외 정답 → Parser/Chunking 대조 → 기관·상품·사건·적용 기간 확인 → SUPPORT/CONDITION/EXCEPTION 관계 검토 → 별도 승인 작업 → READY Generation 연결 → 실제 Golden 검색 평가 순서로 진행한다.

실제 HTML/PDF 원문 저장은 현재 테스트에 필수적이지 않아 수행하지 않았다. 후속 저장 작업은 정확한 URL, 최소 보존 영역, 이용 조건, 파일명을 먼저 확정한다. HWP 기존 파일은 변경하지 않는다. 신규 Migration이나 운영 승인 데이터는 필요하지 않은 단계다.
