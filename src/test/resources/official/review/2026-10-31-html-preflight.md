# 공식 HTML 5건 검토 패키지 (2026-10-31, 승인 전)

이 문서는 V38 Review나 SourceDocument 등록 데이터가 아니다. 공식 페이지의 일부 문단을 직접 열람하고 기존 테스트 전용 `OfficialSourcePreflight`로 수집 bytes를 검사한 결과다. 원문 전체는 저장하지 않았다. 아래 Hash는 조회 순간의 응답에만 해당하며 과거 Seed 판본 또는 운영 DB 판본의 동일성을 증명하지 않는다. 다섯 페이지 모두 HTTP 응답 및 HTML Parser와 UTF-16 Chunk Offset 검사는 성공했지만, 조항·페이지 Locator는 없고 `normalized-content:utf16` 위치만 있다. SourceDocument/Chunk/READY Generation/V38 실제 ID 및 운영 상태는 조회하지 않았다.

| Source key | 공식 URL | 2026-10-31 02:30 UTC Raw SHA-256 | 추출 문자/Proxy Chunk | 검토 범위 및 차단 이유 |
| --- | --- | --- | --- | --- |
| `police-reporting` | https://www.counterscam112.go.kr/board/CONTENT_000000000010.do | `42d188d2473075e356261c9177cb26b305c4a1a4ef819e9a40de65db46d39800` | 3299/5 | 제보방법, 전화·문자 유형, 자료가 없어도 제보 가능하다는 조건은 본문에서 확인. 제보와 송금 피해구제·지급정지는 별도 절차다. Parser 제목은 “통합신고 1394”로 본문 제목 “제보방법 안내”와 다르다. 운영 채널/번호는 재확인 필요. |
| `kb-card-compensation-process` | https://mapps.kbcard.com/SVC/DVIEW/HSGMCXCRSCSC0004 | `5718081436df6bb71be7005f96a41fda14195dd38cf98ae633fecac7624a1416` | 747/2 | 사고 신고·접수·조사·결과 통지 단계, 개인/기업 서류 구분, 카드대출/위변조/대리 접수 조건 확인. KB 기관별 자료로만 검토. HTML 표의 행·열 관계와 조건이 같은 Chunk에 남는지 육안 대조 필요. 보상 결과는 보장하지 않음. |
| `fsc-2026-alert` | https://www.fsc.go.kr/no010101/86271 | `2c808fff35e9d913f4e8ca21ba1c02690a3c4feef5b9c6e4a2e72af160487fba` | 2674/4 | 2026-02-12 빗썸 오지급 사칭 스미싱 경보. 특정 사칭 URL 경고와 악성앱·금전 피해 시의 구별된 안내가 있음. 금융 피해 발생 시 신고·지급정지 문단은 있으나 모든 VOICE/ACCOUNT 피해의 일반 절차 전체를 증명하지 않음. 경보 발행일은 적용 시작일로 확정하지 않음. |
| `fsc-personal-info` | https://www.fsc.go.kr/edu/cardnews?cnId=1527 | `96e3427454b184ead893885fd93ba43384fa9a95e8a70169b2c06b2fd22466a9` | 1017/2 | 2023-01-12 개인정보노출자 사고예방시스템 안내와 등록·해제 설명 확인. 금전 피해 금융회사 신고 문구는 Parser 본문에서 확인되지 않음. 카드뉴스 이미지의 모든 조건이 텍스트 추출에 보존됐는지 미검증. |
| `kisa-118` | https://www.kisa.kr/118 | `a7608ab71e2862a02c347aa07c634c93db20c52cfebcd851c3b74170c3b639cc` | 1020/2 | 118 상담 채널, 분야별 상담 범위, 채널별 운영시간·제약 확인. 상담과 금전 피해 신고·지급정지·구제 신청은 구별해야 함. 운영시간은 변동 가능하므로 재검토 필요. |

첫 조회(02:28 UTC)의 `fsc-2026-alert`, KB, 경찰청 Raw Hash는 각각 `9c31d27161a920b15b41da82260914a646b84b82bdbf3195c19ffd38b9db5724`, `71e7d583a2a6b23f673774f09920f7f43885655825d0064a670d3c28882c3a9f`, `c4ac60945f6a43a71fa7de8e944e8fbe3078bb8652a721d8cd3a352b37298988`였다. 응답 변화의 원인은 미확인이다. 고정 원문 판본 확인 전에는 어느 Hash도 승인 판본으로 사용하지 않는다.

URL 선언도 하나로 합쳐 해석하지 않는다. SourceRegistry용 V10/V17은 위 표 URL을 사용하지만, Emergency payload의 V28은 `fsc-2026-alert`에 `?curPage=2`, `fsc-personal-info`에 `/no040104?cnId=1527`, `kisa-118`에 `www.kisa.or.kr/118`을 기록한다. 이번 Preflight는 SourceRegistry 경로만 확인했다. V28의 다른 URL이 동일한 원문인지, 현재 접근 가능한지 확인하지 않았다.

## V38 검토 후보 (모두 PENDING 제안, 실제 Review 없음)

- 경찰청: 의심 연락 유형별 **제보 방법** 문단만 SUPPORT 후보. 자료가 없는 경우에도 제보 가능하다는 문장을 CONDITION과 함께 확인. 송금 피해구제·지급정지의 직접 근거로 사용 금지. 관련 Scenario는 VOICE/SMISHING 후보이며 사건 유형별 범위 판단 필요.
- KB: KB 카드 사고 접수·조사·통지 단계가 SUPPORT 후보. 개인/기업, 카드대출, 위변조, 대리 접수 문장을 CONDITION/EXCEPTION 후보로 같이 묶어야 한다. `INSTITUTION_SPECIFIC`; 상품·사고일 적용 범위와 원문 판본 미확정.
- FSC 경보: 빗썸 사칭 스미싱 대응 문단이 SUPPORT 후보. 빗썸 보상 안내의 특수성, 실제 자금 이체 여부, 악성앱 설치 여부를 CONDITION으로 분리. `COMMON` 전체 승격 보류. VOICE/ACCOUNT 일반 신고 절차로의 확장은 별도 직접 근거 필요.
- FSC 개인정보: 개인정보 노출 예방과 등록·해제 제도가 SUPPORT 후보. 실제 금전 피해 신고는 지원하지 않음. 노출 확인·명의도용 우려와 거래 제한/해제 조건 검토 전 승인 보류.
- KISA: 118 상담 채널과 상담 분야가 SUPPORT 후보. 시간·채널별 이용 제한을 CONDITION으로 연결. 금전 피해 구제 절차의 SUPPORT로 사용 금지.

## Golden 질문 16건과의 관계

`후보`는 문단과의 주제 연결일 뿐 정답·V38 승인·실제 검색 성공이 아니다.

| Case | 원문 후보와 현재 판단 |
| --- | --- |
| CARD-01 | 다섯 자료 중 기관 미확인 CARD 공통 절차의 직접 근거 없음. 추가 공식 자료 필요. |
| CARD-02 | KB 페이지는 KB 기관별 후보일 뿐 기관 미확인 사용자에게 적용 금지. |
| CARD-03 | KB 페이지에서 체크카드 적용성을 확인하지 못함. 상품 약관 필요. |
| CARD-04 | KB 접수 절차 후보는 있으나 보상 결과·카드 보유 상태·사건일 판단 근거는 없음. |
| VOICE-01 | 경찰청은 제보 후보, FSC 경보는 특정 스미싱 사건 경보. 일반 송금 피해 신고·구제 정답 미확정. |
| VOICE-02 | CARD 자료 차용 금지. 검색 범위 차단 테스트. |
| VOICE-03 | 다섯 자료만으로 지급정지의 대상·예외 전체를 검증할 수 없음. 법령/제도 원문 필요. |
| VOICE-04 | 검색 장애 처리 테스트이며 원문 정답과 무관. |
| ACCOUNT-01 | FSC 경보의 실제 피해 문단만으로 무단이체 공통 제도를 확정할 수 없음. 전자금융거래 법령/제도 원문 필요. |
| ACCOUNT-02 | 다른 은행 약관 차단 테스트. 이 다섯 자료에 해당 은행 약관 없음. |
| ACCOUNT-03 | 사건일 판본 차단 테스트. 과거 시행 판본 미확인. |
| ACCOUNT-04 | Fact 정정/Revision 테스트. 이 다섯 자료에 직접 답 없음. |
| SMISHING-01 | FSC 경보의 특정 사칭 주의, KISA 상담, FSC 개인정보 예방은 서로 다른 주제의 부분 후보. 일반 스미싱 전체 정답 미확정. |
| SMISHING-02 | 철회 Review 차단 테스트이며 운영 Review 상태 미조회. |
| SMISHING-03 | FSC 경보에 악성앱 위험 문단이 있으나 특정 사건 문맥과 조건을 유지해야 함. VOICE 전용 자료 차용 금지. |
| SMISHING-04 | FSC 개인정보 예방은 금전 피해 신고 근거가 아님. 앱 설치·실제 피해 사실 및 별도 신고 근거 필요. |

실제 Answerable Coverage, Precision/Recall, READY 검색 성능은 산출하지 않는다. 이 문서의 원문 관찰은 공식 페이지의 해당 문단 열람에 한정되고, Parser가 모든 조건·예외를 보존했는지 Human Review가 필요하다.

## 실제 활성화 전 필요한 입력

공식 원문 고정 판본과 이용 조건, Raw Hash와 발행/개정/시행일 구분, 원문 문단 대 Parsed Content/Chunk/Locator 대조, SUPPORT/CONDITION/EXCEPTION 관계, 기관·상품·사건 유형·기간의 근거, 검토자/시각/결정 기록이 필요하다. `conditionAndExceptionTextPreserved=true` 고정 메타데이터는 검토 증거가 아니다. 운영 SourceDocument/Chunk/Review/Generation ID를 추측해 만들지 않는다.
