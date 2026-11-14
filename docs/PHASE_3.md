# Phase 3 — 응답 분류와 상태 전이

2026-11-09, 개발자 A, feature/voice-state. 구현 전에 정한 전이 정책이다.
공통 계약/Gradle/SDK/Manifest/B UI·차량·내비는 변경하지 않는다.

## 상태 전이표

위험 단계는 운전자의 자기 보고에 대한 앱의 대응 상태이며 실제 졸음이나 운전 가능 판정이 아니다.
초기값은 state=null/report=UNCONFIRMED. UNKNOWN을 NORMAL로 추정하지 않는다.

| 현재 상태 | 입력 | 다음 상태 / 관측 |
|---|---|---|
| 초기/정상 | 명확한 정상 | NORMAL, 추가 확인 불필요 |
| 초기/정상 | 가벼운 피로 | CAUTION, 추가 확인 필요 |
| CAUTION | 다시 가벼운 피로 | WARNING |
| 초기/정상/CAUTION | 지속 피로 | WARNING |
| 모든 비휴식 상태 | 심한 졸음·운전 곤란 | HIGH_RISK |
| WARNING/HIGH_RISK | 더 약한 피로 답변 | 기존 위험 수준 유지 |
| CAUTION/WARNING/HIGH_RISK | 정상 답변 1회 | RECOVERY_CHECK, 기존 위험 수준은 내부적으로 유지 |
| RECOVERY_CHECK | 연속된 정상 답변 재확인 | NORMAL, 내부 위험 수준 초기화 |
| RECOVERY_CHECK | 피로 재발 | 이전 위험 수준 이상으로 복귀 (최소 CAUTION, 반복 피로는 WARNING) |
| 모든 비휴식 상태 | 모호/상충/미지원 문장 | 상태 유지, report=UNCONFIRMED, 재확인 필요 |
| 모든 비휴식 상태 | NoResponse | 상태 유지, report=UNCONFIRMED, 횟수 증가, 재확인 필요 |
| 모든 비휴식 상태 | Timeout/권한/인식/서비스 오류 | 상태 유지, report=UNCONFIRMED, 무응답 횟수 증가 안 함 |
| 모든 상태 | 안전 정차 VERIFIED + 명시적 onRestStarted | RESTING, 새 상태 답변 대기 중단 |
| 모든 상태 | 정차 NOT_PARKED/UNKNOWN/조회 예외 | 휴식 진입 거절, 상태 유지 |
| RESTING | 상태 답변/무응답/오류 | 무시, 자동 휴식 종료 없음 |
| RESTING | onRestEnded | RECOVERY_CHECK, 정상 답변 2회 확인 전 NORMAL 금지 |
| 비휴식 상태 | onRestEnded | 무시 |

오류/모호/무응답은 연속 정상 확인을 끊는다. 다음 정상부터 다시 2회 확인한다.
NoResponse 외 입력은 연속 무응답 횟수를 0으로 초기화한다. 오류를 무응답으로 세지 않는다.
마지막 명확한 자기 보고 시각만 lastConfirmedAtMillis에 반영한다. 미확인 입력으로 덮어쓰지 않는다.
중복/역순 답변은 마지막 수락 이벤트 시각 이하이면 무시한다. 답변 시각은 음수가 아닌 단조 시계 값이어야 한다.
휴식 진입/종료도 주입 시계로 이벤트 경계를 갱신하여 이전 청취 결과를 차단한다.

## 분류 정책과 제한

외부 생성형 AI나 의료적 추정 없이 정규화된 문장 전체를 명시적 사전과 대조한다.
한글 NFC, 공백 차이, 끝 마침표/느낌표는 허용한다. 의문문·가정·인용·미지원 활용형은 미확인이다.
부정 표현은 긍정 피로 단어의 부분 문자열로 처리하지 않는다.
쉼표 및 명시적 접속어로 나뉜 절이 모두 지원 문장이고 같은 분류일 때만 결합한다.
정상/피로 또는 서로 다른 강도의 상충 답변은 UNCONFIRMED로 재질문하도록 한다.
사전 밖 복잡한 문장도 UNCONFIRMED다. 자연어 전체를 이해한다고 표시하지 않는다.

## 정차 확인 경계

기존 onRestStarted() 시그니처를 바꾸지 않고 구현체 생성자에 A 소유 SafeParkingCheck를 주입한다.
기본값은 UNKNOWN으로 실패 시 닫힌다. B의 향후 실제 차량 상태 검증을 연결할 수 있지만 이번에는 호출하지 않는다.
VERIFIED는 안전한 장소/정차 조건이 최신 상태로 확인됐다는 상위 구현의 결과여야 하며, 단순 음성 “멈췄어”나
속도 0만으로 VERIFIED를 반환하면 안 된다. 구현체는 매 휴식 진입 시 다시 확인한다.
조회는 빠른 동기 스냅샷용이며 실제 SDK 조회/캐싱/유효기간은 B와 후속 통합에서 합의한다.

순수 Kotlin domain/ 안에서 StateFlow를 제공한다. 메모리 내 상태만 유지하고 원문/건강 이력을 저장하지 않는다.
주행 재개, 콘텐츠 변경, 경유지/차량 제어, 경고/TTS, 질문 예약과 타이머는 실행하지 않는다.
followUpRequired는 후속 계층의 입력일 뿐 실제 질문 실행이 아니다.

## 변경 파일 및 검증 결과

- `domain/ConditionClassifier.kt`: 전체 문장 사전 기반 분류와 미확인 처리.
- `domain/DefaultDriverStateManager.kt`: 기존 DriverStateManager 구현, StateFlow, 위험 수준 유지,
  회복 재확인, 정차 확인, 지연/중복 이벤트 차단. 모든 상태 변경은 동기화한다.
- `src/test/.../domain/ConditionClassifierTest.kt`: 매개변수화 문장 사례 54개.
- `src/test/.../domain/DefaultDriverStateManagerTest.kt`: 전이·무응답·오류·회복·정차·휴식·이벤트 시각 테스트 17개.
- `README.md`, 본 문서: 범위/전이/제한 및 결과 안내.

실행: `gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain`.
첫 구현 검증 및 테스트 보완 후 최종 실행 모두 BUILD SUCCESSFUL.
최종 XML 결과는 **91개, 실패 0/오류 0** (신규 Phase 3 71개 + 기존 20개).
`git diff --check` 성공. CRLF 전환 안내는 있었으나 공백 오류는 없었다.

이번 단계에서 Android Studio 화면·Emulator 음성→상태 통합 및 실제 정차 신호는 검증하지 않았다.
분류기의 문장 사전 밖 표현·방언·오인식은 재확인 대상이며 실제 운전자 상태를 진단하지 않는다.
자동 재질문, 위험 경고 실행, 무응답 위험 단계 상승, 경로/차량/콘텐츠 변경은 후속 Phase 범위다.
안전 정차 확인의 실제 데이터 출처/갱신·사용자별 상태 수명주기는 B와 통합 전에 합의해야 한다.
