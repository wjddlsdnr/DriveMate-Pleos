# Phase 0·1 분석 및 공통 설계

확인일: 2026-11-09. 작업 브랜치: `feature/voice-state`. PR 대상: `develop`.
이 문서는 개발자 A의 작업 결과이며 B의 동의나 통합 완료를 의미하지 않는다.

## Phase 0 — 저장소와 명세 차이

기존 프로젝트를 유지했다. 작업 시작 당시 README와 기획안 일부가 미커밋 상태였으며 그 내용을 이어서 보존했다.
`PROJECT_SPEC.md`에 이번에 제공된 1~16장을 반영했다. 기능 목표와 SDK 지원 확인 결과는 별개다.

| 경로 | 기존 구성 및 확인 결과 |
|---|---|
| `app/src/main/.../MainActivity.kt`, `ui/theme/` | Compose 기본 인사 화면과 테마. B 소유로 유지 |
| `app/src/main/AndroidManifest.xml`, `res/` | 일반 Launcher 선언/기본 리소스. 음성·차량 권한, AAOS 기능 및 DO 선언 없음 |
| `app/src/test/`, `app/src/androidTest/` | 기본 산술 단위 테스트 및 패키지명 계측 테스트 |
| 루트/앱 Gradle, `gradle/` | 단일 `app` 모듈, 버전 카탈로그, Wrapper, JVM 도구 체인 |
| `settings.gradle.kts` | Google/Maven Central, Pleos Maven 미등록 |
| `.idea/` | 기존 Android Studio 설정. 수정하지 않음 |
| `docs/` | 기존 부분 명세를 전체 명세로 확장, 분석·개발 경계 문서 추가 |
| SDK 라이브러리 | 저장소에 Pleos AAR/JAR 및 로컬 API 문서 없음. 확인한 기본 사용자 Gradle 캐시와 실제 빌드 캐시에 Pleos 그룹 없음 |

명세 3~16장의 프로필·음성·상태 판단·주기적 확인·단계별 대응·휴식·차량/콘텐츠 연동은 기존 코드에 구현되지 않았다.
이번 변경도 공통 타입/인터페이스 선언만 추가한다. 화면, SDK Adapter, Mock 실행체, 상태 머신, 분류기, 스케줄러와 타이머 구현은 없다.

### 기존 버전과 로컬 환경

| 항목 | 확인 값 | 판단 |
|---|---|---|
| Gradle Wrapper | 9.6.0, 기존 SHA-256 검증 설정 | 유지 |
| AGP | 9.5.1 | 유지 |
| Kotlin Compose plugin | 2.2.10 | 유지. AGP 내장 Kotlin과 별도의 Kotlin Android 플러그인은 추가하지 않음 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 30 | 유지. Pleos 실기기 호환성은 미검증 |
| Java 소스/타깃 | 11 | 유지 |
| Gradle daemon JVM | 도구 체인 25, 로컬 Java 25.0.4 | 기존 설정 유지 |
| Compose BOM | 2026.02.01 | 유지 |
| 선언된 AndroidX | core 1.10.1, lifecycle runtime 2.6.1, activity Compose 1.8.0 | 전이 제약에 의해 최종 버전은 달라질 수 있음 |
| coroutines core | 기존 runtime 해석 결과 1.9.0 | 새 StateFlow 계약의 직접 의존성으로 동일 버전 명시 |
| 로컬 SDK platform | `android-37.0` (API 37.0) | 설치 확인 |
| Pleos system image | API 34, `pleos_car_emulator/x86_64`, revision 1, vendor 42dot | 설치 확인, 실행 검증 아님 |
| AVD | `Pleos_Connect_v2.1.0` | 등록 확인. 이미지 내 Speech/Vehicle 서비스 버전은 미확인 |

`local.properties`의 로컬 SDK 경로를 읽었으며 변경하거나 버전 관리에 추가하지 않았다.
시스템 이미지 설치는 앱용 SDK 의존성 설치와 다르다. 프로젝트에서 해석 가능한 Pleos SDK가 없어 바이너리와 문서의 버전·시그니처 비교는 수행할 수 없었다.

### 공식 문서 확인 결과와 기능 분류

아래 링크는 2026-11-09에 직접 열어 확인했다. 문서의 SDK 버전은 후보 정보이며 프로젝트 의존성으로 도입한 버전이 아니다.
API Reference 루트 URL은 읽을 수 있는 본문이 없었지만, SDK 가이드와 STT 하위 Reference 탐색은 가능했다.
SDK 호출을 추가하기 전 정확한 오버로드·반환값·콜백·권한·서비스 버전을 설치 아티팩트와 다시 대조해야 한다.

| 기능 / 담당 | 확인된 지원 | 권한·조건 및 미확인 항목 | 현재 코드 |
|---|---|---|---|
| STT / A | [STT 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro): 3.3.1, 음성을 텍스트로 반환 | [STT_SERVICE](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/declaring-permissions), 마이크 환경·개인정보 설정 검증 필요. 서버 모드는 앱 등록 필요 | 포트만 정의 |
| TTS / A | [TTS 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro): 3.3.1, HYBRID/SERVER/ON_DEVICE | [TTS_SERVICE](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/declaring-permissions), 오디오 포커스·완료/중지 확인 필요 | 포트만 정의 |
| 상태 분류·스케줄·휴식 / A | 앱 내부 순수 Kotlin으로 구현 가능 | 의학적 확정 판단 금지, 가상 시간 테스트·주행 상황 확인 필요 | 모델/계약만 정의 |
| 목적지·경유지 / B | [NaviHelper 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro): 3.0.5, 경로/경유지 요청 제공 | [NAVI_ROUTE / NAVI_ROUTE_SEARCH / NAVI_CUSTOM_MAP](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions). 실제 사용 API에 필요한 것만 선택. ETA 구체 필드와 휴식 POI 검색·방향/접근성 미확인 | 요청 포트만 정의 |
| HVAC·Window·Seat / B | [Vehicle 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro): SDK 2.3.2 / Service 2.3.21 | [권한 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission)의 조회용 CAR_CLIMATE/CAR_WINDOWS/CAR_SEATS 확인. 제어용 정확한 권한·범위·변경 원인 판별은 개별 API 확인 필요 | 공조/환기 제안·동의·복원 포트만 정의, 좌석 제어 없음 |
| 차량 프로필·시동 자동 실행 / B 및 통합 협의 | 이번에 확인한 문서로 직접 연동 가능 여부 확정 불가 | 차량 사용자 식별, 자동 시작 정책, 보안·백그라운드 제한 추가 확인 | A용 프로필 조회/선호도 계약만 정의 |
| 음악·뉴스 / A 해석, B 연동 | STT 텍스트 이후 앱이 명령 처리해야 함 | 제3자 앱/휴대전화 제어·서비스 로그인·검색 권한 미확인 | 미디어 명령 모델/포트만 정의 |
| Gleo·말동무 / A | [Gleo Interface](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts)는 App Schema 기반 Function Calling | 앱 주도 다단계 자유 대화/LLM 세션 제공은 미확인. 제한 대화는 후속 Phase에서 구현 | 미구현, 외부 생성형 AI API 없음 |
| 위험 도로 구간 / B 데이터, A 발화 협의 | 별도 데이터 접근 확인 필요 | 내비 알림 중복 방지·주행 제한 확인 | 미구현 |
| 긴급 지원 / 별도 검토 | 공식 연동 지원 미확인 | 무응답만으로 자동 119 신고 제외 | 계약 및 호출 없음 |

[개발 시작 가이드](https://document.pleos.ai/docs/pleos-only/getting-started)는 Android SDK API 34와 minSdk 28 이상을 제시한다. 현재 minSdk 30은 이를 충족하나 target 37 조합의 검증을 대신하지 않는다.
동 가이드의 Vehicle 예제는 2.3.1, [Vehicle 설치 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup)는 2.3.2를 제시한다. 임의로 하나를 채택하지 않았다.

### 음성 Adapter 구현 전 확인 메모

- [STT 시작/종료](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/start-and-stop): `request()` 전 초기화/리스너 준비, `stop()`으로 종료.
- [STT 리스너](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners): `onUpdated(stt: String, completed: Boolean)`, `onEndedRecognition()`, `onError(errorType: ErrorType)` 문서 확인. 중간 결과를 최종 답변으로 소비하지 않는다.
- [TTS 리스너](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/event-listeners): `onDone()` 재생 완료와 `onStop()` 중지를 구분한다. `speak()` 반환을 재생 완료로 취급하지 않는다.
- [STT 오류](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/error-code): TIMEOUT은 서비스 지연일 수도 있으므로 무응답 횟수를 증가시키는 근거로 바로 사용하지 않는다. 명확한 무응답을 구별할 수 없으면 미확인/오류로 처리한다.
- [STT Reference](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/speech-to-text/ai.pleos.playground.stt/speech-to-text/entry) 탐색 가능. 설치 SDK와 대조 전 모든 시그니처 검증이 끝났다고 보지 않는다.

### 앱 카테고리와 주행 규제

[카테고리 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-category)에 비추어 **Mobility Services를 우선 검토 후보**로 제안한다. 운전 지원/기존 내비 연계가 목적이기 때문이며 이는 설계상 추론이다. 자체 지도/경로 안내 앱으로 심사받을 경우 Maps/Navigation 적용을 별도로 확인한다. 음악 기능만으로 전체 앱을 Music/Audio로 정하지 않는다. 최종 카테고리는 B와 플랫폼 심사 기준에 맞춰 확정해야 한다.

[주행 안전 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline)는 CarUxRestrictions와 오디오 포커스 준수를 요구한다. [지역별 규제](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy)는 DO 미선언 시 주행 중 사용 불가를 설명한다. 현재 앱은 주행 중 사용 가능하다고 표시할 수 없다. DO 선언만으로 기능 허용을 보장하지도 않는다.

[내비게이션 개발 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/navigation-app-development-guide)의 단일 경로 정책과 사용자 선택에 따른 경로 전환을 준수해야 한다. 기존 내비와 동시에 자체 경로 음성을 출력하지 않는다. B가 UI/정책 상태를 전달하고 A는 위험 구간에서 일반 질문과 말동무를 보류하는 통합 설계를 후속 작업에서 구체화한다. Manifest 카테고리/권한/DO 선언은 이번에 변경하지 않았다.

## Phase 1 — 공통 코드

구현 위치는 `com.example.pleosconnect.contract`이다. 모든 이름은 앱 자체 계약이며 Pleos SDK 클래스나 함수가 아니다. 구현체는 아직 없으며 실제 SDK 연동이나 Mock 시뮬레이션으로 표시하지 않는다.

| 파일 | 정의한 내용 |
|---|---|
| `contract/model/DriverModels.kt` | 6개 상태, 상태 미확인 관측, 모드, A가 읽는 프로필, 공통 성공/실패, 동의 범위 |
| `contract/voice/VoiceService.kt` | TTS 완료 후 STT 시작 계약, 음성 단계, 무응답/시간 초과/권한/인식/서비스 오류 |
| `contract/integration/IntegrationPorts.kt` | B의 내비/검색/미디어/차량/프로필 구현을 위한 포트 |
| `contract/logic/LogicPorts.kt` | 상태 관리자, 질문 스케줄러, 대화 제어기 및 가상 시간용 주입 시계 |

상태 초기값은 `state=null`, `report=UNCONFIRMED`로 둔다. 초기 상태를 정상으로 추정하지 않으며, 무응답 관측과 위험 대응 단계를 별도로 보관한다. 정상 응답 판별 및 상태 전이 규칙은 Phase 3·5에서 구현한다.

시간은 한 프로세스/부팅 세션 내 단조 시계의 밀리초 값과 Kotlin `Duration`으로 전달한다. 타임아웃·질문 지연은 양의 유한값이어야 하며, 구현체가 검증해야 한다. 경로 ID/개정값으로 재탐색을 식별하고 일반/위험/회복 질문을 구분한다. Phase 4에서 중복 제거와 동시 질문 우선순위를 구현·테스트한다.

`DriverConsent`는 명시적 응답의 전달 모델이지 신뢰할 수 있는 권한 토큰이 아니다. B 구현은 제안 ID, 범위, 시각, 현재 경로, 철회 여부를 검증해야 한다. 차량 변경 전 상태 저장·수동 변경 항목 복원 제외를 계약으로 명시했으며 실행으로 강제하는 코드는 아직 없다.

## 수명주기 및 후속 검증

- A의 대화 상태는 ViewModel에서 관찰하되 Activity를 소유하지 않는다. B가 화면/Composition과 ViewModel 연결을 담당한다.
- 백그라운드/포커스 상실 시 음성 취소, 마이크 종료, SDK 리스너 해제 및 재등록을 검증한다. 안전 안내가 허용되는 서비스 모델은 카테고리·백그라운드 정책 확인 후 결정한다.
- 프로세스 재생성 시 진행 중 STT, 동의, 경로 소유권, 차량 설정 복원 권한을 자동 복원하지 않는다. 최신 경로/차량 상태를 다시 조회하고 운전자에게 재확인한다.
- B가 저장하는 선호도와 A의 일시적 건강/응답 정보를 분리한다. 원문 음성/건강 이력의 기본 영구 저장은 하지 않는다.
- 타이머의 프로세스 종료·재부팅 복구 방식은 Phase 9에서 결정한다. 종료 후 RECOVERY_CHECK와 운전자 선택을 거치며 자동 주행 재개하지 않는다.

## 검증 기록

- Phase 0 변경 전: `gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain` 성공. 기존 기본 산술 테스트 실행.
- 최초 sandbox 실행은 기본 Gradle 캐시 lock 경로 쓰기 제한으로 시작 실패. 승인된 동일 명령으로 실행했다.
- `dependencyInsight`로 기존 coroutines-core 1.9.0 해석 확인 후 동일 버전을 직접 의존성으로 명시.
- Phase 1 변경 후: 동일 `assembleDebug` / `testDebugUnitTest` 명령 성공 (`BUILD SUCCESSFUL`, 46초). 공통 Kotlin 코드 컴파일 및 Debug APK 생성 확인. 기존 단위 테스트 1개, 실패 0개, 오류 0개.
- `git diff --check` 성공. `libandroidx.graphics.path.so` 심볼 제거 불가 메시지는 있었으며 라이브러리를 그대로 패키징했고 빌드는 성공했다.
- Emulator/Virtual Console/실차, 마이크·Speech 서비스, 차량 권한, API 34에서 target 37 동작은 이번 단계에서 검증하지 않았다.
- 신규 실행 로직이 없는 선언 단계이므로 구현을 그대로 반복하는 테스트는 추가하지 않았다. 기존 테스트 통과는 안전 요구사항이나 계약 준수의 검증이 아니다.

## 다음 개발 지시 제안 — Phase 2만

“`docs/DEVELOPMENT_BOUNDARIES.md`의 공통 계약을 검토하고 Phase 2만 진행하세요. Pleos STT/TTS 아티팩트·서비스 버전·권한·콜백을 공식 문서와 대조한 후 A의 `voice/`와 `pleos/voice/`에 Adapter를 구현하세요. 설치/권한을 확인할 수 없으면 `mock/voice/`의 명확히 표시된 Mock으로 대체하고 실제 연동 완료로 보고하지 마세요. TTS 완료 후 STT 시작, 중복 요청 직렬화, 취소·마이크 해제, 권한 거부·무응답·인식 오류·서비스 타임아웃 분리를 자동 테스트하세요. UI와 B 담당 SDK 호출은 수정하지 말고, 상태 머신·질문 스케줄러는 후속 단계로 남기세요. Gradle 빌드/단위 테스트와 별도의 Pleos Emulator 검증 결과를 보고하세요.”
