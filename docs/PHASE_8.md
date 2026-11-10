# Phase 8 — Gleo 지원 범위와 제한 음성 말동무

2026-11-10, feature/voice-state, 개발자 A. 구현 전에 지원 범위·대화표·소유권을 정의한다.

## 공식 문서/설치 SDK 대조

| 항목 | 확인 결과 / 이번 구현 |
|---|---|
| 제3자 앱 음성 함수 호출 | 공식 Gleo Interface는 App Schema의 함수를 Gleo Action으로 호출하고 Callback을 받는 방식. 앱의 등록/실행 검증은 이번에 하지 않음 |
| 자유 LLM 대화/앱 주도 Gleo 세션 | 열람한 문서와 설치 아티팩트에서 공개 세션 API 확인 못 함. 미지원이라고 단정하지 않고 미확인으로 남김 |
| Gleo 문맥 유지 | 플랫폼의 함수 호출 설명을 앱용 대화 이력/세션 API 제공으로 해석하지 않음. 미확인 |
| 앱 주도 짧은 음성 질문 | 설치 STT/TTS 3.3.1의 speak/request 및 완료/인식 콜백 확인. 기존 VoiceService로 정해진 대화만 구성 |
| 설치 SDK | Gradle 직접 의존성과 ai.pleos.playground 캐시에 SpeechToText/TextToSpeech 3.3.1 존재. 별도 Gleo 대화 SDK 의존성 없음 |
| AmbientAi 이름의 내부 API | STT 바이너리에는 관련 API와 호출자/시스템 앱 검사 및 SecurityException 경로가 있음. 이름만 보고 공개 LLM API로 사용하거나 제한을 우회하지 않음 |

2026-11-10 공식 페이지 직접 확인:
- [Gleo 핵심 개념](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts)
- [App Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability)
- [Gleo Action/Callback 예제](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples)
- [Schema 전달](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema)
- [STT 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro)

로컬 검사: gitignore된 build/sdk-inspection의 실제 AAR classes.jar에 javap 실행하여
SpeechToText/TextToSpeech의 메서드와 ApiChecker를 대조했다. 바이너리는 커밋하지 않는다.
Function Calling을 자유 대화 구현으로 표시하지 않는다. App Schema 등록·Intent/Manifest 변경·외부 생성형 AI API는 추가하지 않는다.

## 제한 대화표

| 단계 | 입력/처리 |
|---|---|
| 시작 | NORMAL 및 명시 선택된 READY/COMPANION, 포그라운드일 때 사용자가 start 호출. “음악과 풍경 중 어떤 이야기를 원하세요?” |
| 1차 답변: 음악/음악 이야기 | 정해진 짧은 음악 안내 후 “발라드와 재즈 중 어떤 쪽을 좋아하세요?” |
| 2차 답변: 발라드/재즈 | 해당 주제의 고정된 한 문장으로 종료. 재생/선호도 저장 없음 |
| 1차 답변: 풍경/풍경 이야기 | 정해진 짧은 안내 후 “산과 바다 중 어디를 좋아하세요?” |
| 2차 답변: 산/바다 | 고정된 한 문장으로 종료. 실제 현재 도로·위치·날씨를 안다고 말하지 않음 |
| 그만/대화 그만/말동무 그만/조용히/중단 | 즉시 종료, 추가 청취 없음 |
| 피로·졸음 자기 보고 | 대화 중단 및 needsConditionCheck 표시. 건강 판단/상태 전이/무응답 단계 증가는 하지 않음 |
| 미지원·상충 문장 | 제한된 주제만 가능하다고 짧게 알리고 종료, 반복 질문 안 함 |
| 침묵·STT 오류/권한/시간 초과 | 종료, 구별된 실패/미확인 결과. 졸음으로 추정하지 않음 |

세션당 청취 최대 2회, 세션 전체 60초, TTS 30초/청취 10초 제한이다.
문맥은 세션 내 주제 하나뿐이며 종료 시 폐기한다. 원문 발화·대화 이력·개인 취향을 저장하지 않는다.

## 수명주기·음성 소유권

- A의 LimitedCompanionController가 기존 ConversationController를 구현한다. 공통 계약/Phase 0~7 소스는 유지한다.
- 입력 StateFlow: Phase 5 safety.response, Phase 7 mode status, B가 제공할 selectedProfile/foreground 상태.
  포그라운드 입력은 필수이며 초기 false로 조립해야 한다. B 화면/lifecycle 코드는 이번에 변경하지 않는다.
- 비정상/미확인·restStopNeeded·모드 변경·프로필 변경·foreground=false면 활동 세션을 취소하고
  VoiceService.stop 완료를 기다린다. 다시 정상/포그라운드가 돼도 자동 시작하지 않는다.
- onBackground는 내부 차단도 latch하고 즉시 pause한다. onForeground는 latch 해제만 하며 대화를 시작하지 않는다.
- withSafetyPriority { ... } / requestConditionCheck는 대화 정리 후 안전 작업에 음성 소유권을 넘긴다.
  안전 작업 동안 start는 거절하며, 종료 후에도 자동 재개하지 않는다. 안전 작업은 별도 mutex로 직렬화한다.
- 상위 소유자는 Phase 7 질문 완료 후에만 start를 호출하고, 안전 질문·Phase 5 경고/Phase 6 안내는
  withSafetyPriority 안에서 실행한다. 이 경계를 거치지 않는 임의의 VoiceService 직접 호출까지 통제하지는 않는다.
- 실제 VoiceService는 Phase 2 SerializedVoiceService를 사용해야 하며 speak 성공을 실제 TTS 완료로 보장해야 한다.
  STT 시작 전 TTS 완료를 기다리고 취소 정리가 끝나기 전 새 세션을 받지 않는다.
- start 진입 시 VoicePhase.IDLE이 아니면 다른 소유자의 음성을 중단하지 않고 시작을 거절한다.
  이 확인은 전역 원자적 lease가 아니므로 모든 상위 음성 작업의 withSafetyPriority 사용/호출 순서 규칙이 여전히 필요하다.
- 정리 예외 또는 stop 후 VoicePhase.UNAVAILABLE이면 정리 실패를 latch하고 새 대화/안전 음성 인계를 거절한다.
  상위 계층은 오디오 서비스를 재확인·재생성해야 하며, 정리 실패를 무시하고 겹쳐 재생하지 않는다.
  전체 60초 제한은 대화 작업에 적용되며 소유권 안전을 위한 정리 대기 시간은 별도다.
- conditionCheck 콜백은 상위 A 대화 계층이 제공한다. 말동무 안에 상태 질문·주행 스케줄/위험 대응을 중복 구현하지 않는다.
- 원문 없이 needsConditionCheck를 관찰한 상위 계층은 대화 종료 뒤 상태 확인을 요청한다.
- 졸음 응답 이후에는 이전 NORMAL 관측으로 새 대화를 시작하지 못하도록 차단을 유지한다.
  conditionCheck 콜백은 상태 확인/갱신 완료까지 기다려야 하며, 새 시각의 NORMAL 확인이 끝난 뒤에만
  차단을 해제한다. 해제 뒤에도 사용자의 새 start가 필요하다. 상태 재확인을 자동 의료 판단으로 취급하지 않는다.
  이번 단계는 B UI와 실제 lifecycle 조립·실차 음성 통합을 완료했다고 표시하지 않는다.
- Phase 9 타이머, 실제 콘텐츠 재생/차량 제어/긴급 연락, 외부 AI API를 추가하지 않는다.

## 변경 검토·검증 결과

- 추가: `voice/LimitedCompanionController.kt`, 해당 단위 테스트, 본 문서. README에 Phase 8 안내 추가.
- 기존 Phase 0~7 소스·공통 계약·SDK 의존성/버전·Manifest·B 담당 UI/차량·내비 코드는 유지했다.
- `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain` 최종 실행 BUILD SUCCESSFUL.
- XML 결과: **285개, 실패 0/오류 0** (기존 251개 + 신규 34개).
- Fake/가상 시간으로 두 주제·2회 제한·60초 제한, TTS 완료 후 STT, 침묵/권한/서비스 오류,
  위험·미확인·모드·프로필·백그라운드 취소, 정상/포그라운드 복귀 시 자동 재개 금지,
  졸음 응답 후 재확인 latch, 중복 시작·정리 중 재시작 금지, 안전 작업 직렬화/소유권 인계와
  정리 실패 시 차단을 검증했다. 프로필 저장과 실제 미디어 API는 호출하지 않는다.
- Lint 오류 0/기존 경고 24. 의존성 버전 권고·미사용 리소스·JNA 16KB 정렬 및 lifecycle custom lint
  일부 검사 생략 포함. 이 단계에서 버전을 임의 변경하지 않았다.
- `git diff --check` 성공. 기존 미추적 less 도움말 파일은 수정/커밋하지 않는다.

**미검증:** 실제 Gleo Schema 등록/Action/Callback 실행, 공개 자유 대화 세션·문맥 API,
Pleos Emulator/실차에서 말동무의 음성·foreground/lifecycle·오디오 포커스와 안전 안내 인계,
실제 앱 ViewModel/UI 조립. Fake 성공을 Gleo/실차 연동 완료로 해석하지 않는다.
이전 Phase 2의 실제 STT/TTS 검증과 이번 제한 대화의 Fake 검증은 별개다.
Foreground 상태만으로 주행 중 기능 허용/심사 적합성을 보장하지 않으며 CarUxRestrictions와 플랫폼 사용 정책은 B/통합 단계에서 확인한다.
