# Phase 10 — A 영역 통합 및 B 인수인계

## 사전 확인: 누락된 연결과 해결 방향

- 개별 조정기의 VoiceService 직접 호출은 상위 소유권 없이는 충돌한다.
  DriveMateSession의 단일 실행 경로로 제한하고 안전 확인은 일반 작업을 취소·정리 후 실행한다.
- 휴식 조정기는 DriverStateManager를 직접 갱신하지만 Phase 5 response는 별도 snapshot이다.
  통합 snapshot은 최신 driver 상태를 합성하며 휴식·회복 후 NORMAL을 반영한다.
- 경로 갱신→스케줄러, due 질문→음성→상태 대응→휴식 장소 요청의 연결이 없었다.
  통합 tick/initialCheck가 연결한다. 실제 polling은 상위 lifecycle 소유자의 책임이다.
- 사용자 변경과 background는 전체 작업을 취소하고 콘텐츠를 중단해야 한다.
  사용자 변경은 세션을 영구 무효화한다. B는 close 후 새로운 세션을 생성해야 한다.
- 공통 contract, B UI/실제 SDK/저장소, Gradle/Manifest는 변경하지 않는다.

## 흐름 및 음성 소유권

통합 진입점만 호출한다. 내부 조정기를 외부에서 직접 실행하면 안 된다.
initialCheck → 정상일 때 모드 질문 / 피로 대응 → 필요 시 검색·후보 동의 →
안전 정차 후 restCommand → tick에서 만료·회복 확인 → resumeAfterRest 명시적 동의.

일반 모드·말동무는 단일 작업만 허용한다. conditionCheck/initialCheck는 활성 일반 작업을
cancelAndJoin한 후 안전 우선 소유권에서 실행한다. 안전 작업끼리는 직렬화한다.
모든 TTS 완료 후 STT를 시작하고 작업 종료 때 stop을 기다린다. 정리 실패는 세션을
무효화하며 재실행하지 않는다. RestVoiceOwner는 이미 확보한 소유권 안에서 실행한다.
Phase 8의 withSafetyPriority가 말동무를 정리하며 다른 안전 안내와도 동일 경로를 공유한다.

휴식 중 일반 질문 스케줄은 취소한다. 회복·재개 과정에서 상태는 자동 NORMAL로 바뀌지
않으며 Phase 9의 2회 정상 확인 및 별도 재개 동의를 따른다. 재개 성공 후 최신 ETA로
주기적 확인을 다시 구성하되 출발 확인 중복은 억제한다.

## B 연결 순서와 입력 포트

| B 제공 값 | 연결 책임 |
|---|---|
| CoroutineScope | 화면보다 긴 세션 소유 범위. 종료 전 반드시 suspend close() 호출 |
| VoiceService | 실제 Pleos Adapter 초기화 완료 후 주입. 앱 종료 때 공유 서비스 close |
| NavigationController | 유효한 ETA와 단조 observedAtMillis·routeId·증가 revision 제공, null은 경로 종료 |
| RestStopFinder | 진행 방향·접근성·합법 주차를 검증한 후보만 반환 |
| VehicleController | 지원 여부·권한 검증, 명시적 동의 및 원본 설정/수동 변경 보존 |
| MediaController | 실제 재생/중단/변경, 실패를 Success로 반환하지 않음 |
| DriverProfileRepository | 선택 사용자 StateFlow와 선호도 저장. A는 요청만 수행 |
| SafeParkingCheck | VERIFIED는 실제 안전 주차 검증. 속도 0/운전자 발화만으로 추정하지 않음 |
| MonotonicClock | 모든 A/B observedAtMillis와 같은 단조 시간 기준 |
| RestSessionStore | FileRestSessionStore에 앱 전용 파일 제공. 다른 사용자로 전환 시 기존 세션 데이터 정리 |

1. 프로필 확인·목적지 설정은 B에서 완료한다. 위 포트를 주입해 DriveMateSession을 생성한다.
2. 활성 경로가 준비되면 initialCheck() 호출. 같은 routeId 출발 확인은 한 번만 수행한다.
   실패/무응답 재확인은 conditionCheck()로 명시적으로 요청한다.
3. UI는 driverStatus/safetyStatus/modeStatus/restStatus/guidanceStatus/pendingChecks/failure를 관찰한다.
4. lifecycle 활성 상태에서 tick()을 정기 호출한다. 20~30분을 UI에서 직접 계산하지 않는다.
   일반 tick은 작업 중 건너뛰며, due 안전 확인은 일반 대화를 정리한 후 실행한다.
   이미 안전 대화가 진행 중이면 새 요청을 시작하지 않는다. due는 다음 tick까지 보존한다.
5. 사용자가 명시적으로 모드 변경을 요청하면 modeCommand(text), 말동무 시작은 companionChat().
   말동무에서 피로가 언급되면 별도의 상태 확인으로 이어진다. 답변을 자동 확정하지 않는다.
6. 휴게소 도착만으로 RESTING에 진입하지 않는다. 안전 주차 검증 후 restCommand()로 시간 질문.
7. tick에서 만료·회복 질문이 실행된다. 회복 확인에 실패하면 recoveryCheck(),
   재개는 resumeAfterRest()로 별도 동의 질문을 호출한다. 자동 차량 출발은 없다.
8. background 전에 onBackground()를 await한다. 복귀 시 onForeground()는 실행 허용만 하며
   대화는 자동 재개하지 않는다. lifecycle 변경 호출은 상위 소유자가 순서대로 await해야 한다.
9. 사용자 변경은 기존 세션을 무효화한다. close() 완료 후 저장된 휴식 세션을 정리하고 새
   포트/세션을 생성한다. 프로필 변경은 자동 관찰하므로 과거 동의가 이어지지 않는다.
10. CarUxRestrictions·오디오 포커스·긴급 안전 안내가 상호작용을 금지하면 cancel()을 await하고
    허용될 때만 onForeground() 호출. 외부 안전 음성을 동시에 직접 실행하지 않는다.
    UI·안전 시스템 음성도 이 소유권 경계를 따라야 하며 현재 A 세션 밖 실행은 통제하지 못한다.
11. 앱 종료 시 close()를 await한다. 상위 scope 취소도 진행 중 작업을 취소하지만,
    리소스 정리 완료를 보장받는 정상 종료 경로는 close()이다. close는 idempotent다.

## 남은 작업과 실동작 검증

- B Compose/ViewModel·실제 프로필·POI·NaviHelper·차량·미디어 Adapter 조립은 미구현.
- 자동 시동 실행, 권한/서비스 가용성, 주행 중 앱 사용 제한, 오디오 포커스는 실제 환경 확인 필요.
- Gleo 자유 대화 API는 여전히 미확인. 제한 말동무만 사용하며 외부 AI API를 추가하지 않았다.
- 타이머는 UI/상위 소유자가 tick을 공급하는 동안 작동한다. background에서는 A 통합 tick이
  음성을 실행하지 않으며 저장은 마지막 체크포인트를 사용한다. 정확한 OS 알람은 보장하지 않는다.
- 프로세스 복구 시 B는 같은 사용자임을 확인한 후 restoreRest()를 명시적으로 호출한다.
  사용자 일치가 확인되지 않으면 복구하지 않는다. 종료 중 경과 시간을 추정하지 않는 Phase 9 정책 유지.
- 공조 설정 복원은 별도 새 동의·수동 변경 검증과 함께 B/A 후속 연결이 필요하며 이번 통합에서
  자동 복원하지 않는다. 위험 구간 정보·음성 중복 조정도 B 데이터/SDK 검증 후 연결해야 한다.
- 실제 Korean TTS→STT의 이전 Phase 2 결과는 유지하지만 이번 통합 세션의 Emulator 실동작은
  실행하지 않았다. Fake 통과를 실제 차량/SDK 통합 성공으로 간주하지 않는다.

## 실제 검증 결과와 변경 파일

- 실행: `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain`.
- Debug 빌드 성공, 전체 330개 단위 테스트 통과(통합 테스트 신규 18개), 실패/오류 0.
- Lint 오류 0, 기존 경고 24. SDK/Gradle 버전 및 기존 Phase 0~9 소스 변경 없음.
- 통합 Fake/가상 시간: 초기 확인 중복 방지→조용한 모드→20분 주기 확인→CAUTION과
  동의 후 환기 요청→5분 후 지속 피로→검색/동의/경유지→주차 검증→20분 휴식→
  정상 2회 확인→별도 동의 후 기존 경로 재개와 중복 재개 차단을 검증했다.
- 심한 졸음 즉시 검색, 말동무 피로 재확인, 모드 변경/안전 확인에 의한 대화 취소,
  due 질문 우선권, 일반 tick의 안전 대화 방해 방지, STT 오류/무응답 구별,
  사용자/경로/background/상위 scope 종료 취소, TTS 중 취소와 정리 실패 차단도 검증했다.
- 실제 Emulator/Virtual Console 통합 세션 실행은 미실시. 실제 SDK 성공으로 표시하지 않는다.

변경 파일은 A의 `domain/session/DriveMateSession.kt`, 대응 통합 테스트,
`docs/PHASE_10.md`, README 네 개뿐이다. 공통 계약과 B 영역은 변경하지 않았다.

## PR #1 후속 수정 — 휴식 후 동일 경로 질문 복원

휴식 시작/복구에서 cancelAll()이 경로 ID를 종료 목록에 넣어 재개 후 예약이 사라지는 결함을
수정했다. 공통 계약·B 포트/코드는 바꾸지 않고 A 구현에 pauseForRest()/resumeAfterRest()를
추가했다. cancelAll(), null 경로, 0 ETA는 계속 경로 종료로 처리한다.

- 휴식 일시 정지는 질문/위험 후속 예약을 비우지만 경로를 종료하지 않는다.
- 같은 revision이면 정지 시점의 남은 시간을 동결한다. 새 revision은 휴식 중에도 검증하여
  남은 ETA를 갱신하되 질문은 예약하지 않는다. 휴식 시간이 주행 시간으로 차감되지 않는다.
- 회복 확인 및 명시적 내비 재개 성공 뒤 남은 시간을 재분할한다. 회복 직후 출발 질문은
  생성하지 않고 이후 구간 질문을 예약한다. 반복 재개 호출은 예약을 바꾸지 않는다.
- 경로 취소/도착으로 종료된 ID는 재개나 새 revision으로 되살리지 않는다.
- 변경: DefaultCheckScheduler, DriveMateSession, 두 대응 테스트 및 이 문서만 수정.
- 검증: assembleDebug + testDebugUnitTest + lintDebug 성공. 전체 338개 테스트 통과
  (회귀 테스트 신규 8개), 실패/오류 0. Lint 오류 0·기존 경고 24.
- 가상 시간/Fake로 동일·새 revision의 예약과 실제 후속 질문, 중복 질문 방지,
  휴식 중 ETA 갱신, 반복 일시 정지/재개, 취소·도착의 종료 동작을 확인했다.
- Emulator/실차 검증은 이번 수정에서 실행하지 않았다. 기존 실제 음성 기록과 구분한다.
