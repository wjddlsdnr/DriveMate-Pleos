# B1 — 명시적 Fake와 최소 UI·ViewModel 연결

작업일: 2026-11-10 (Asia/Seoul). 브랜치 `feature/b-integration`, PR 대상 `develop`.
기능 기준은 PROJECT_SPEC.md, 작업 범위는 B_DEVELOPMENT_PLAN.md의 B1이다.

## 구현 범위

- 기본 인사 화면을 시뮬레이션 전용 Compose 화면으로 교체했다. 운전자 상태, 최신 응답의 미확인 여부, 가상 모드/경로/미디어 명령, 휴식 남은 시간, 예약 수, 실패를 표시한다.
- 가상 운전자 2명과 정상/피로 지속/심한 졸음/시간 초과/권한 거부/무응답 시나리오를 제공한다. 응답과 동의는 고정 스크립트이며 실제 사용자 발화가 아니다. 스크립트 소진은 기존 MockVoiceBackend의 NoResponse로 처리한다. 같은 시나리오를 다시 선택하면 초기화된다.
- 가상 목적지 설정 버튼이 명시적 체험 동의 진입점이다. 정상 흐름 뒤 상태 재확인, 조용한 모드/가상 음악/뉴스/말동무 요청을 세션에 전달한다. UI에 표시되는 모든 목적지/재생/차량 변경은 시뮬레이션이다.
- B의 FakeNavigationController, FakeRestStopFinder, FakeVehicleController, FakeMediaController, FakeDriverProfileRepository를 추가했다. 성공뿐 아니라 권한 거부/미지원/서비스 실패 등을 주입할 수 있다. 실제 차량 값·좌표·콘텐츠 서비스는 사용하지 않는다.
- FakeConsentValidator는 범위·대상·미래 시각·30초 경과·중복 소비를 확인한다. 30초는 B1 테스트 정책이며 Pleos 공식 규칙 또는 A/B 최종 합의가 아니다. 검색 요청/후보/경로 revision도 검증한다. 실제 권한 토큰을 구현한 것이 아니다.
- 가상 차량 변경 핸들/제안은 일회 소비하며 가상 수동 변경 후 복원을 거부한다. A 세션의 차량 설정 복원 진입점 추가는 B1에서 하지 않았다.
- FakeSafeParkingCheck 기본값은 UNKNOWN이다. UI에서 VERIFIED를 설정할 수 없다. 속도 0/발화/가상 휴식 장소 도착으로 주차를 확정하지 않으며, 휴식 요청은 차단 이유를 표시한다. 따라서 B1 화면에서 실제 휴식 타이머·회복·재개를 체험 완료한 것으로 주장하지 않는다.
- InMemoryRestSessionStore와 Fake 프로필은 메모리 전용이다. 재시작 복구/영속 저장/OS 알람은 구현하지 않았다.

## 조립과 수명주기

DriveMateAppGraph는 기존 A의 MockVoiceBackend와 SerializedVoiceService를 조립한다. B 래퍼는 질문 문구와 음성 오류 종류만 UI에 전달하며 운전자 응답 분류·상태 전이·질문 스케줄·음성 우선순위를 재구현하지 않는다. 실제 마이크/스피커/SDK는 호출하지 않는다.

DriveMateSessionOwner는 A의 DriveMateSession만 통합 진입점으로 사용한다. 공개 Flow를 UI 상태로 투영하고, foreground에서 단일 1초 polling 작업으로 tick을 공급한다. 20~30분 스케줄은 A가 계산한다. UI 작업 중 중복 버튼 요청은 버리고, lifecycle과 리셋은 별도 직렬 제어 경로로 처리한다.

MainActivity의 onResume/onPause 이벤트는 ViewModel의 채널에서 순서대로 처리된다. background 전환 처리에서는 polling 취소 완료 → session.onBackground 완료 → 실행 작업 정리 순서를 지킨다. Android callback 자체를 동기적으로 막지 않으며, 처리 중 새 foreground 요청도 같은 채널에서 기다린다. 복귀 시 대화를 직접 다시 시작하지 않는다. 복귀 후 도래한 주기 질문은 A의 tick 정책에 따른다.

ViewModel은 화면 재구성/Activity 재생성을 넘어 세션을 보존한다. onCleared에서는 이미 취소될 viewModelScope에 정리를 맡기지 않고 별도 소유 scope의 worker finally에서 close 완료를 기다린 뒤 scope를 종료한다. 즉시 폐기하는 경우에도 finally가 설치되도록 worker를 UNDISPATCHED로 시작한다. 프로세스 강제 종료에는 suspend 정리 완료를 보장할 수 없으며 다음 실행은 새 메모리 세션이다.

프로필 전환/시나리오 리셋은 기존 polling·대화 취소 → 상태 관찰 취소 → session.close → 공유 VoiceService.close → 휴식 저장소 삭제 → 새 프로필/포트/세션 생성 순서다. 다른 사용자에게 동의·경로·휴식 상태를 넘기지 않는다.

## 변경 파일

Kotlin 패키지 기준은 `app/src/main/java/com/example/pleosconnect/`다.

- `MainActivity.kt`: 기존 테마 재사용, Compose/ViewModel 및 onResume/onPause 연결.
- `ui/DriveMateScreen.kt`, `DriveMateUiState.kt`, `DriveMateViewModel.kt`: 화면/상태/명령·수명주기 큐.
- `integration/DriveMateAppGraph.kt`, `DriveMateSessionOwner.kt`: Fake 조립과 세션 소유자.
- `mock/integration/`: Fake 포트 5개, 동의 검증기, 주차 확인, 메모리 휴식 저장소.
- `app/build.gradle.kts`, `gradle/libs.versions.toml`: lifecycle-viewmodel-ktx를 기존 lifecycleRuntimeKtx 버전 참조로 명시. Gradle/SDK/기존 버전 값 변경 없음. Manifest/권한 변경 없음.
- `app/src/test/.../integration/DriveMateSessionOwnerTest.kt`, `mock/integration/FakeIntegrationTest.kt`: B 연결/실패/동의/수명주기 단위 테스트.
- `app/src/androidTest/.../ui/DriveMateScreenTest.kt`: B1 시뮬레이션 화면 계측 테스트.
- 본 문서와 README의 B1 안내 링크. 기존 계획 문서는 분석 당시 기록으로 보존한다.

`domain/`, `voice/`, `scheduler/`, `pleos/voice/`, `mock/voice/`, `contract/`의 A 파일은 수정하지 않았다.

## 발견한 A 연계 한계

최초 상태 질문에 RecognitionResult.PermissionDenied/Timeout을 주입하면 driver.report는 UNCONFIRMED지만 SafetyResponseCoordinator.respond의 미확인 분기에서는 오류 종류를 failure에 넣지 않는다. 따라서 session.failure와 safetyStatus.failure만으로는 UI가 구체 오류를 구분할 수 없다. 재현: 해당 Fake 시나리오 선택 → 가상 목적지 시작 → 세션 Flow 관찰. B1은 백엔드 결과의 오류 종류만 별도로 관찰해 표시한다. 공통 계약이나 A 판단 로직은 변경하지 않았다. 실제 음성 교체 전에 A의 오류 관찰 계약을 협의할 항목이다.

또한 driver.state가 직전 NORMAL인 채 report가 UNCONFIRMED일 수 있다. B 화면은 이때 ‘미확인’을 표시해 이전 정상 응답을 현재 정상 확인으로 오인하지 않게 한다. 위험 상태 자체는 A의 값 그대로 유지한다.

## 검증 기록

- 첫 제품 코드 빌드/기존 테스트/lint 성공 후 B 테스트를 추가했다.
- 추가 테스트 첫 실행에서 구체적 음성 오류 표시 테스트 1개가 실패했다. 위 A 관찰 한계를 확인하고 B 표시 경로를 보완한 뒤 재실행했다.
- 최종 자동 검증 결과와 Emulator 결과는 아래 완료 기록에 기재한다. 단위 테스트/Fake의 통과는 실제 SDK 성공이 아니다.
- 첫 Pleos 화면 테스트 시도는 4개 중 1개 통과, 3개는 Compose hierarchy 준비 전 실패였다. 초기 화면 대기를 추가했다. 같은 실행 중 system 서비스 예외와 /sdcard 접근 실패가 있어 저장 snapshot을 불러오지 않는 재부팅으로 재검증한다. 기기 데이터 초기화나 시스템 앱 비활성화는 하지 않았다.

### 최종 완료 기록

- 실행: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --console=plain` → `BUILD SUCCESSFUL in 2m 22s`. Debug APK 및 UI 테스트 APK 생성 성공.
- 생성된 단위 테스트 XML 합산: 354개 통과, 실패/오류/건너뜀 0. 기존 A 테스트 338개에 B 테스트 16개를 추가했다. Flow 반영/가상 명령 전달/foreground 단일 polling/background 중 취소/무자동 대화 재개/프로필 전환/즉시 ViewModel 폐기/음성 해제/오류·동의·stale 요청·수동 변경 차단을 검증했다.
- Lint 오류 0, 경고 25. 기존 문서의 24와 비교해 ViewModel 의존성 최신 버전 안내 경고가 추가됐다. 버전은 올리지 않았다. 기존 lifecycle lint의 API 비호환으로 `UnsafeLifecycleWhenUsage` 및 `RepeatOnLifecycleWrongUsage` 검사가 제외됐다는 경고도 남아 있으므로 모든 lifecycle 정적 검사가 실행됐다고 주장하지 않는다.
- 네이티브 라이브러리 3개의 심볼 제거 불가 메시지가 있었으며 그대로 패키징됐다.
- 실제 Pleos Connect v2.1.0, API 34, `emulator-5554`에서 앱/테스트 APK 설치 후 `am instrument -w -r -e class com.example.pleosconnect.ui.DriveMateScreenTest ...AndroidJUnitRunner` 실행: `OK (4 tests)`, 32.436초. 테스트 내용은 최초 시뮬레이션/미확인 표시, 정상 Fake 흐름과 사용자 전환, 가상 권한 오류 표시, Activity 재생성 시 세션 보존이다. 화면 준비 대기 보완과 snapshot 미로드 재시작 뒤 모두 통과했다.
- 이번 검증은 실제 Emulator 위의 **Fake 앱 연결과 UI** 검증이다. 실제 STT/TTS·NaviHelper·Vehicle·콘텐츠 SDK·Virtual Console·실차·운전 중 사용 허용/카테고리 심사·OS 정밀 알람은 검증하지 않았다. 권한 거부도 Fake 결과를 주입한 것이며 실제 시스템 권한 거부 실험이 아니다.
- A 보호 영역 변경 없음과 Git 공백 검사를 확인했다. 커밋/push/PR 생성/병합은 하지 않았다.
- 실제 Emulator 화면 캡처(`app/build/b1-preview.png`, Git 제외)로 가로 2열 배치, 시뮬레이션 표기, 초기 미확인·안전 주차 미확인 표시와 버튼 배치를 확인했다. 검증용으로 실행한 백그라운드 Emulator는 종료했다. 첫 /sdcard 캡처 실패 뒤 shell 임시 경로 `/data/local/tmp`를 사용해 캡처에 성공했다.

## 실행 방법과 다음 단계

### 사용자 수동 테스트 후 재확인

사용자가 피로 지속 시나리오에서 ‘상태 다시 확인’을 눌러도 경고로 바뀌지 않는다고 보고했다. `DriveMateSessionOwnerTest.fatigueRecheckAtActualPlaybackSpeedReachesWarning`를 추가해 제품 기본 Mock 재생 지연 600ms와 1초 polling을 그대로 사용했다. 초기 대화 완료 후 재확인으로 WARNING 및 가상 경유지 추가 1회를 확인했으며 이 조건에서는 문제가 재현되지 않았다. 제품/A 코드는 변경하지 않았다. 추가 후 assembleDebug와 전체 단위 테스트 355개가 통과했다. 사용자의 실제 화면에서 발생한 중단·버튼 비활성·현재 안내 변화는 아직 확인되지 않았으므로 원인 해결로 표시하지 않는다.

Android Studio에서 내부 저장소 `DriveMate-Pleos/DriveMate-Pleos`를 열고 app을 실행한다. ‘시뮬레이션 모드’ 표기를 확인하고 시나리오를 선택한 뒤 ‘가상 목적지 설정에 동의하고 시작’을 누른다. 피로 지속 시 ‘상태 다시 확인’으로 A의 후속 대응을 확인할 수 있다. 체험 종료/앱 이탈 시 실제 음성이나 차량 제어는 없다.

다음 작업은 B2 프로필 저장이다. B1을 유지하면서 B 내부 프로필 등록·조회·선택·삭제와 선호도 저장을 구현하고 FakeDriverProfileRepository만 실제 로컬 저장소로 교체한다. 사용자 전환의 close 후 재생성 규칙, 원문 음성/건강 이력 미보관, 백업/추출 정책을 유지한다. 실제 내비·차량·미디어는 각각 B3~B5로 남긴다.
