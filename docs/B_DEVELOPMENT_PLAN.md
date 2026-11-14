# 개발자 B 개발 계획 및 인수 분석

최신 미디어: [B5-2 사용자별 음악 앱 설정과 실제 음성 연결](B5_VOICE_MEDIA.md). 프로필별 기본 앱 저장과 A 세션의 명시적 실제 음성 시작, 음악 중단 선점을 구현했다. 로컬 실제 재생→TTS 완료는 검증했으나 사용자 발화 전체 흐름/외부 음악 앱 재생/뉴스는 미검증 또는 미구현이다. 아래 B5-1 및 최초 분석은 당시 기록이다.

미디어 후속: [B5-1 로컬 오디오와 미디어 서비스 연결](B5_MEDIA.md). 사용자가 고른 로컬 파일의 foreground 실제 재생과 포커스/백그라운드 중단을 구현했다. 생성 WAV의 실제 재생 진행·중단은 native 계측으로 확인했다. 외부 서비스는 2개 검색되지만 재생은 미확인이다. 실제 음성 및 안전 선점, 뉴스 제공자 연동은 남아 있으며 B5 전체 완료가 아니다. 가상 세션의 FakeMediaController는 유지한다.

설정 제어 후속: [B4-2 직접 동의하는 설정 변경](B4_VEHICLE_CONTROL.md). 실제 SDK 준비/구독과 B 화면의 변경·복원 동의 경계를 구현했다. A 음성 제어 연동과 실제 변경/복원 검증은 후속이며 온도 조절·주행 중 자동 환기는 미지원이다.

차량 후속: [B4-1 실제 차량 조회](B4_VEHICLE_READ.md). 기어/AAOS 속도·주차 브레이크/공조·창문 조회 경계를 연결했으며 차량 설정 변경·수동 변경 출처 판별·안전한 장소 확인은 별도 후속이다.

후속 구현 현황: B1/B2 이후 [B3 실제 내비 연결](B3_NAVIGATION.md)을 추가했다. 실제 연결·상태·즐겨찾기 조회는 검증했으며 안전 휴식 장소 데이터 연결과 실제 변경 명령 검증은 남아 있다. 아래 분석은 최초 작성 시점의 기록이다.

확인일: 2026-11-10 (Asia/Seoul). 이번 범위는 분석·계획·기본 검증이며 UI/SDK 구현은 하지 않는다.
기능 기준은 PROJECT_SPEC.md, 경계는 DEVELOPMENT_BOUNDARIES.md, 통합 기준은 현재 DriveMateSession 코드다.

## 1. 작업 저장소와 보존 사항

- 실제 작업 루트: `C:\Users\User\StudioProjects\DriveMate-Pleos\DriveMate-Pleos`.
- 브랜치: `feature/b-integration`, PR 대상: `develop`. 시작 시 내부 저장소 미커밋 변경 없음.
- HEAD: `17fae7d` (PR #1 병합), 직전 `ffa7def`에 휴식 후 주기 질문 복원 수정 포함.
- 상위 폴더도 별도 Git 저장소이며 `develop`의 초기 프로젝트다. 상위에는 `.idea/.name` 삭제 및 내부 저장소 미추적 표시가 있었다. 그대로 보존한다.
- 원격은 `https://github.com/wjddlsdnr/DriveMate-Pleos.git`. 원격 fetch/push/병합은 하지 않았다. 원격 최신성과 로컬 develop 일치는 별도 미검증이다.
- AGENTS.md, README.md, PROJECT_SPEC.md 전체, DEVELOPMENT_BOUNDARIES.md, PHASE_10.md, PHASE_2_EMULATOR.md를 확인했다. 과거 Phase 0·1 기록도 현재 코드와 대조했다.
- A의 `domain/`, `voice/`, `scheduler/`, `pleos/voice/` 및 공통 contract를 변경하지 않는다.

## 2. 현재 프로젝트와 환경

단일 `app` 모듈, Kotlin/Compose 프로젝트다. MainActivity는 `Hello Android!` 기본 화면이며 ViewModel/앱 조립/B 실구현은 없다. 기존 theme/res를 재사용한다. A 단위 테스트와 실제 음성 전용 계측 테스트는 존재한다.

| 항목 | 현재 선언 및 관찰 |
|---|---|
| Gradle / AGP | Wrapper 9.6.0 (SHA-256 지정) / 9.5.1 |
| Kotlin Compose / Compose BOM | 2.2.10 / 2026.02.01 |
| Android | compileSdk 37 / targetSdk 37 / minSdk 30 |
| Java | 소스·타깃 11, daemon toolchainVersion 25, 현재 JAVA_HOME은 JDK 17. 실제 빌드 daemon 로그에서 Eclipse Adoptium Java 25 사용 확인 |
| 코루틴 | core 및 test 1.9.0 |
| AndroidX 선언 | core 1.10.1 / lifecycle runtime 2.6.1 / activity Compose 1.8.0; 전이 해석 버전과 구별 |
| Pleos 의존성 | SpeechToText 3.3.1, TextToSpeech 3.3.1만 선언. NaviHelper/Vehicle/미디어 SDK 없음 |
| 저장소 | Google, Maven Central, Pleos Maven(그룹 필터 적용) |
| Manifest | STT_SERVICE/TTS_SERVICE 및 Launcher. B 차량/내비 권한·앱 카테고리·주행 제한 연결은 없음 |
| 로컬 Android SDK | ANDROID_HOME 설정됨. 최초 platform 목록 30/33/34/34-ext8/35/36, 빌드가 요구한 37.0 revision 2 자동 설치 완료 |
| 로컬 설정 | 내부 프로젝트 local.properties 없음. 기존 환경 변수 사용. 설정 파일/버전 변경 없음 |

## 3. A 구현·B 미구현·미검증 구분

| 기능 | 실제 코드의 A 구현 | B 잔여 작업 | 검증 한계 |
|---|---|---|---|
| 음성 | SerializedVoiceService, PleosVoiceBackend, MockVoiceBackend | 초기화 완료된 음성 서비스 주입·앱 종료 해제 | PHASE_2_EMULATOR 후속 기록은 실제 한국어 TTS→STT 최종 결과 수신 성공. 이번 PC에서 재실행 안 함 |
| 상태/피로/무응답 | ConditionClassifier, DefaultDriverStateManager, SafetyResponseCoordinator | 상태 표시와 실패 안내 | Fake 테스트와 실제 졸음 판정은 다름. 무응답/서비스 실패는 불확실 |
| 주기/통합 | DefaultCheckScheduler, DriveMateSession | 경로/ETA 공급, lifecycle 활성 중 tick 호출 | PHASE_10 최신 기록 338개 성공은 과거 결과. 현재 실행 결과는 8절 |
| 프로필/선호도 | DriverPreferences 투영 모델과 조회·저장 요청 | 등록/조회/선택/영속 저장/삭제, 사용자 격리 | 실제 저장소 없음. 이름만으로 신원 확인하지 않음 |
| 정상 모드/콘텐츠 | NormalDrivingCoordinator/Parser, LimitedCompanionController | 실제 음악 검색·재생/뉴스/오디오 | 자유 Gleo 대화나 외부 서비스 제어는 미확인 |
| 목적지/경유지/재개 | RestStopGuidanceCoordinator 및 RestSessionCoordinator 요청 | 목적지 설정, ETA/개정 발행, 동의 검증, 실제 내비 호출 | 현재 SDK 어댑터/POI 데이터 없음 |
| 휴식 장소 | 검색·후보 안내·음성 동의 요청 | 진행 방향/접근성/합법 주차 검증·후보 수명 관리 | 단순 가까운 거리만으로 안전한 후보 확정 불가 |
| 차량 | 보조 제안/동의 후 apply 요청, 수동 변경 관찰 | HVAC/Window 조회·변경·복원·수동 변경 감지 | 복원 자동 연결 없음. 제어 권한/출처 구분 미검증 |
| Seat | 공통 차량 계약에 좌석 제어 없음 | 정차 조건 검증 후 B 전용 조회·저장·복원 설계 | SDK 단위·범위/권한 미검증. 주행 중 이동 금지 |
| 휴식 | 타이머/최소 체크포인트/2회 회복 확인/별도 재개 동의 | 안전 주차 판정, 저장 파일 제공, 복구 사용자 확인 | tick 기반이며 OS 정확 알람/프로세스 종료 중 경과 보장 없음 |
| UI/수명주기 | 세션의 공개 메서드/Flow | Compose/ViewModel/소유자/권한·제한·오디오 포커스 | 기본 인사 화면만 존재, 통합 Emulator 실행 안 함 |
| 위험 구간/자동 실행 | 통합 경계만 문서화 | 데이터 접근·내비 음성 중복 조정·시동 자동 실행 조사 | 카테고리/심사/공식 지원 미확정 |

## 4. DriveMateSession 실제 연결 계약

파일: `app/src/main/java/com/example/pleosconnect/domain/session/DriveMateSession.kt`.
생성자 인자는 순서대로 `parentScope: CoroutineScope`, `voice: VoiceService`, `navigation: NavigationController`, `finder: RestStopFinder`, `vehicle: VehicleController`, `media: MediaController`, `profiles: DriverProfileRepository`, `parking: SafeParkingCheck`, `clock: MonotonicClock`, `store: RestSessionStore`다.
SafeParkingCheck는 `domain/DefaultDriverStateManager.kt`, RestSessionStore는 `domain/rest/RestSessionCoordinator.kt`의 A 공개 타입을 소비한다. 위치를 옮기거나 계약을 중복 선언하지 않는다.

| 공개 관찰 값 | 현재 StateFlow 값 타입 / B 사용 |
|---|---|
| driverStatus | DriverStatus: state는 null 가능, report=UNCONFIRMED를 정상과 구별 |
| safetyStatus | SafetyResponse: 휴식 필요·실패·차량 변경 핸들·수동 변경 |
| modeStatus | NormalModeStatus: 모드·단계·선호도 저장 결과 |
| restStatus | RestStatus: 단계·남은 시간·응답·실패; 원문 응답을 영구 기록하지 않음 |
| guidanceStatus | RestGuidanceStatus: 후보·단계·실패 |
| pendingChecks | List<ScheduledCheck>: 관찰만 하고 B가 질문 시각 재계산하지 않음 |
| failure | FailureReason?: 정리/권한/서비스 오류 표시 |

공개 suspend 메서드: `initialCheck()`, `conditionCheck(kind: CheckKind = RISK_FOLLOW_UP)`, `modeCommand(text: String)`, `companionChat()`, `restCommand()`, `restoreRest()`, `recoveryCheck()`, `resumeAfterRest()`, `tick()`, `onBackground()`, `cancel()`, `close()`. `onForeground()`만 일반 함수다.

- 프로필·목적지·유효 경로 준비 후 initialCheck. routeId별 최초 1회이며 실패 재확인은 conditionCheck 사용.
- 일반 명령과 안전 질문은 세션만 호출한다. A 내부 조정기를 직접 호출하거나 B 음성 루프를 병행하지 않는다.
- 소유자가 한 개의 주기 작업으로 활성 상태에서 tick 공급. 주행/위험 스케줄은 A 소유다.
- background 전에 onBackground를 await. 복귀의 onForeground는 허용만 하며 대화 자동 재개 없음.
- 프로필 ID 변경은 기존 세션을 영구 무효화한다. close 완료, 기존 사용자 휴식 데이터 정리, 새 세션 생성 순서를 지킨다.
- UI 제한/오디오 포커스 상실은 cancel 완료 후 차단. 허용 시에만 onForeground 호출.
- 정상 종료는 상위 scope 취소 전에 suspend close를 완료한다. ViewModel.onCleared에서 이미 취소된 scope에 launch하는 방식으로 해제를 보장하지 않는다. 화면 재구성과 세션 소유자를 분리한다.
- 공유 VoiceService.close는 앱 소유자가 한 번 수행한다. 동일 사용자 확인·안전 주차 검증 전 restoreRest 호출 금지.

### B 구현 포트

`contract/integration/IntegrationPorts.kt`와 `contract/model/DriverModels.kt`는 현재 변경하지 않는다.

| 포트 | 구현해야 할 실제 멤버 | 핵심 조건 |
|---|---|---|
| NavigationController | route: StateFlow<RouteProgress?>; setDestination(DestinationRequest, DriverConsent); addRestWaypoint(RestStopSearch, RestStopCandidate, DriverConsent); resumeRoute(String, DriverConsent) | suspend 메서드는 OperationResult<Unit>. routeId/증가 revision/Duration remaining/동일 단조 시계 observedAtMillis. null은 경로 종료 |
| RestStopFinder | suspend find(RestStopSearch): OperationResult<List<RestStopCandidate>> | requestId/routeId/revision에 후보를 묶고 오래된 결과 거부 |
| VehicleController | manualOverrides: Flow<VehicleAdjustment>; suspend propose(VehicleAssist), apply(VehicleAssistProposal, DriverConsent), restore(VehicleAdjustment, DriverConsent) | 결과는 각각 Proposal/Adjustment/Unit. 변경 전 원본 보관, 수동 변경 존중, 복원은 새 동의 |
| MediaController | suspend execute(MediaCommand): OperationResult<Unit> | PlayMusic(query), Stop, Next, PlayNewsAudio. 실패를 성공으로 반환 금지 |
| DriverProfileRepository | selectedProfile: StateFlow<DriverPreferences?>; suspend savePreferences(DriverPreferences): OperationResult<Unit> | 등록/전체 프로필/선택 API는 B 내부에서 설계. 다른 사용자에 저장되지 않도록 검증 |
| SafeParkingCheck | current(): SafeParking | VERIFIED/NOT_PARKED/UNKNOWN. 실제 근거 없으면 UNKNOWN, 속도 0/발화만으로 VERIFIED 금지 |
| MonotonicClock | nowMillis(): Long | Android 단조 시계로 A/B 시간 기준 통일; 테스트는 가상 시계 |
| RestSessionStore | load(): RestCheckpoint?; save(RestCheckpoint?) | 원자 저장, 개인 데이터 없음. A FileRestSessionStore에 앱 전용 파일 주입 가능 |

DriverConsent는 인증 토큰이 아니다. scope/targetId/시각/철회/일회 사용/현재 제한을 B가 검증해야 한다. A 현재 생성값은 차량 보조 proposal.id, 경유지 candidate.id, 재개 route.routeId다. B가 내부 제안·검색·현재 revision과 결합해 검증한다. 목적지 동의 UI는 B 책임이다. 동의 유효 기간은 미합의이며 임의로 SDK 규칙이라 주장하지 않는다.

## 5. 공식 문서와 설치 SDK 대조 범위

2026-11-10 공식 문서에 접근했다. 아래 가이드 버전은 프로젝트에 추가한 버전이 아니다.

- [SDK 개요](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/): 차량 기능 capability 확인 및 승인된 권한 범위 필요.
- [NaviHelper 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro): 문서 기준 3.0.5. 목적지/경유지 및 이벤트 제공. initialize/addListener/removeListener/release 수명주기 설명 확인. 설치 바이너리 시그니처 대조 전 호출 구현 금지.
- [NaviHelper 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions): NAVI_ROUTE, NAVI_ROUTE_SEARCH, NAVI_CUSTOM_MAP을 기능별 구별. 실제 함수 @RequiresPermission을 확인한 뒤 최소 권한만 추가.
- [Vehicle 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro): 문서 기준 SDK 2.3.2 / Service 2.3.21. capability/get/set/callback 구조 확인.
- [Vehicle 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission): 조회 CAR_*와 제어 CONTROL_CAR_* 구분. HVAC/Window/Seat의 개별 함수·정확 권한·차량 지원 범위는 설치 SDK/API Reference 대조 전 미확인.
- [내비 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/navigation-app-development-guide): 단일 경로/사용자 선택에 따른 전환/카테고리 검토. 내비를 보조하는 본 앱의 카테고리를 임의로 확정하지 않는다.

프로젝트 안에 Pleos AAR/JAR은 없다. 빌드 전 기본 사용자 캐시 조회에서는 확인되지 않았으나 빌드 후 SpeechToText/TextToSpeech 3.3.1 AAR이 Gradle 캐시에 존재함을 확인했다. 기존 음성 어댑터는 이 의존성으로 컴파일됐다. B SDK(NaviHelper/Vehicle)는 현재 캐시에 없으며 선언·설치·서비스 버전·메서드 오버로드·콜백·권한의 바이너리 대조를 완료하지 못했다. Fake 연결 단계에는 B 실제 SDK를 추가하지 않는다. SDK 교체 단계마다 아티팩트와 서비스 버전, 권한, capability, 콜백 완료/실패, 해제 동작을 별도 증거로 남긴다.

## 6. B 파일 계획 및 단계별 완료 조건

아래 Kotlin 경로는 `app/src/main/java/com/example/pleosconnect/` 기준 제안이며 이번에는 생성하지 않는다. 공통 계약/Gradle/Manifest 변경은 필요 이유와 A/B 영향을 먼저 설명한다.

| 단계 | B 파일 계획 | 구현 및 완료 조건 |
|---|---|---|
| B1 Fake 최소 연결 | MainActivity.kt, ui/DriveMateScreen.kt, ui/DriveMateViewModel.kt, ui/DriveMateUiState.kt, integration/DriveMateSessionOwner.kt, integration/DriveMateAppGraph.kt, mock/integration/FakeNavigationController.kt, FakeRestStopFinder.kt, FakeVehicleController.kt, FakeMediaController.kt, FakeDriverProfileRepository.kt, FakeSafeParkingCheck.kt, InMemoryRestSessionStore.kt | DriveMateSession만 사용. MockVoiceBackend+SerializedVoiceService 재사용 검토. 화면에 시뮬레이션 명시, 가짜 목적지/재생/차량 결과는 실제 성공으로 표시 금지. 기본 주차 UNKNOWN. 주차 VERIFIED는 테스트 전용 시나리오만. 오류/미지원/권한 거부/오래된 요청을 주입 가능하게 하고 기본 성공만 반환하는 Fake 금지 |
| B2 프로필 | data/profile/DriverProfile.kt, LocalDriverProfileRepository.kt, ProfileStorage.kt, ui/profile/ | 등록/목록/선택/저장/삭제와 사용자별 선호도 영속 저장. 이름 대신 고유 ID. 저장 방식은 의존성 확인 후 선택. 동시 저장/사용자 전환/프로세스 재생성 검증, 음성 원문·건강 이력 저장 금지. 백업·추출 정책 검토 |
| B3 내비/휴식 검색 | pleos/navigation/PleosNavigationController.kt, PleosRestStopFinder.kt, NavigationConsentValidator.kt | 실제 SDK 지원 조사부터 수행. 목적지 동의, ETA→RouteProgress, 경유지/원래 경로 재개, 후보/동의 수명 검증. 안전 후보 데이터 부족 시 UNSUPPORTED/빈 결과. 재탐색·취소·권한 거부·오래된 콜백·휴식 후 같은 경로 주기 질문 확인 |
| B4 차량/주차 | pleos/vehicle/PleosVehicleController.kt, VehicleSettingsSnapshot.kt, VehicleSafeParkingCheck.kt, SeatSettingsController.kt | capability와 권한 확인 후 조회부터. 실제 안전 주차 근거 검증 전 UNKNOWN. 명시적 동의 후 HVAC/Window 변경, 원본 보관·수동 변경/부분 실패·새 동의 후 복원. Seat는 별도 정차 조건/명시적 동의, 주행 이동 금지 |
| B5 미디어 | data/media/ServiceMediaController.kt, MediaServiceGateway.kt, ui/media/ | 서비스 선택·로그인·검색·실제 재생 완료 기준 조사. 음악/뉴스/다음/중단과 안전 경고 선점, 오디오 포커스 상실/오프라인/미지원 구분. 인증정보 커밋 금지 |
| B6 실환경 통합 | integration/ 소유자, MainActivity.kt, 필요 시 Manifest/res/Gradle (사전 설명) | 실제 음성으로 단계적 교체, foreground/background/사용자 전환/프로세스 복구, 마이크 거부·서비스 실패·제한 상태 검증. 위험 구간 안내 중복 조정·시동 자동 실행·정확 OS 알람은 지원 조사 후 별도 계획. Emulator/Virtual Console/실차 결과 구분 |

B1 테스트 계획: B ViewModel/소유자와 Fake 포트에 대해 Flow 반영, UNKNOWN 표시, 명령 전달, 중복 tick 방지, background 시 취소·복귀 무자동 대화, 프로필 변경 시 close 후 새 세션, 실패 표시와 stale 동의 거부를 검증한다. A 로직을 그대로 복제한 테스트/구현은 추가하지 않는다. 매 단계 assembleDebug/testDebugUnitTest, UI/권한 변경 시 lint 및 적절한 계측 검증을 수행한다.

## 7. A 협의 항목 및 재현 가능한 확인 조건

이번 분석에서 A 결함을 확정하거나 수정하지 않았다. 다음은 현재 구현 한계/연계 협의이며 B에서 우회 로직을 중복 구현하지 않는다.

- 차량 복원: SafetyResponse에 adjustment와 manualOverride가 있지만 DriveMateSession에 명시적 복원 동의 진입점이 없다. 재현 조건은 환기 동의→정상 회복→설정 복원 요청이다. B의 restore 구현만으로 통합 자동 복원이 연결되지 않으므로 A의 새 동의 흐름 협의가 필요하다.
- 위험 구간: 세션 공개 메서드에 위험 구간 이벤트가 없다. 내비 위험 안내 중 일반 질문이 due인 조건에서 중복 억제를 실제 검증하고, A는 질문/음성 우선순위, B는 이벤트/제한 공급을 담당한다. 현재는 cancel/onForeground 경계를 사용할 수 있지만 상세 재개 정책은 협의한다.
- 목적지 음성/프로필 질문: 세션은 경로 준비 뒤 컨디션 확인부터 시작한다. 사용자 등록·목적지 설정은 B가 완료하지만 A 음성 소유권과 충돌하지 않는 초기 질문 연계는 별도 협의한다. B1은 Fake 목적지 선택으로 범위를 한정한다.
- background 알람: tick은 usable 조건으로 차단되므로 정밀 OS 알람을 보장하지 않는다. background 만료·프로세스 종료 조건은 현재 정책대로 미보장으로 표시하고 변경 필요 시 A 타이머 영향부터 설명한다.
- 공통 계약 변경 필요 시 별도 제안: 현재 타입은 그대로 소비한다. 동의 철회/일회 소비/안전 주차 근거/제한 이벤트에 추가 계약이 필요한지는 B 어댑터 조사 후 변경 이유·A 호출 영향·마이그레이션을 제시한다.

## 8. 이번 검증 결과

- 실행 명령: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain`.
- 최초 제한 환경 실행은 사용자 Gradle 캐시 lock 쓰기 접근 거부로 시작 실패. 같은 명령을 권한 승인 경로로 재실행했다.
- 재실행 중 Android SDK Platform 37.0 revision 2가 자동 설치되었다. Gradle/SDK 선언 버전은 수정하지 않았다.
- 최종 결과: `BUILD SUCCESSFUL in 2m 49s`, 43개 작업 실행. Debug APK 생성 및 기존 단위 테스트 실행 성공.
- 이번 생성된 `app/build/test-results/testDebugUnitTest/TEST-*.xml` 13개를 합산: 테스트 338개, 실패 0, 오류 0, 건너뜀 0. 과거 문서의 숫자를 재사용한 판정이 아니다.
- 네이티브 라이브러리 3개(libandroidx.graphics.path.so, libjnidispatch.so, libuacore.so)의 심볼 제거 불가 메시지는 있었으며 그대로 패키징됐다. 빌드 실패는 아니다.
- 변경 파일은 본 문서 하나다. lint는 이번에 실행하지 않았다. 최종 문서 공백 검사도 수행한다.
- 이번 Emulator/계측/Virtual Console/실차/실제 B SDK 검증은 미실시. 새 테스트/제품 코드는 추가하지 않았다.

## 9. 다음 단계 구체적 개발 지시

> feature/b-integration에서 B1만 구현하세요. 이 계획과 실제 DriveMateSession 계약을 기준으로 B 전용 명시적 Fake 5개 포트·UNKNOWN 기본 주차·메모리 휴식 저장소를 만들고, 기존 MockVoiceBackend/SerializedVoiceService를 연결한 최소 Compose UI·ViewModel·세션 소유자를 구성하세요. 모든 화면에 시뮬레이션임을 표시하고 초기 미확인 상태/실패를 정상으로 바꾸지 마세요. A 내부 로직이나 공통 contract는 수정하지 말고 세션 공개 진입점만 사용하세요. foreground tick·background await 취소·프로필 전환 close 후 재생성·공유 음성 해제를 검증하세요. 실제 SDK/프로필 영속 저장/내비/차량/미디어 연결은 다음 단계로 남기세요. 필요한 Gradle/Manifest 변경 이유를 먼저 설명하고 빌드·기존 테스트·B 연결 검증 결과와 미검증 항목을 한국어로 보고하세요.
