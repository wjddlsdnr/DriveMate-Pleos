# 개발자 B 인수인계 — 2026-11-12

## 2026-11-13 A 검증 작업을 다시 받는 경우 — 최신

최신 음성 수정/검증 인수인계 브랜치는 **`feature/voice-state`**다.
기존 B 통합 커밋 `e9d9679` 위에 재질문 수정·질문 문맥 해석·회귀 테스트·비식별 진단을 추가했다.
이번 사용자의 요청으로 해당 브랜치에 커밋·푸시한다. main/develop 및 B 브랜치는 변경하지 않는다.
아래의 2026-11-12 B 작업 기록과 구분해 이 절을 먼저 읽는다.

- 설계: `docs/VOICE_REPROMPT.md`
- 실제 관찰/자동 검증: `docs/VOICE_MEDIA_VALIDATION_2026-11-12.md`
- 재질문 TTS 후 STT 재시작, 재질문/취향 질문 횟수 제한을 구현했다.
- 알려진 단일 상태 질문의 예/아니요 문맥 해석과 ‘안 졸림’ 등을 추가했다.
- 공통 계약과 B UI/차량/내비/외부 콘텐츠 동작은 유지했다. LiveVoiceIntegration에는
  debug 전용 원문 없는 진단만 추가했고 로컬 재생 허용 규칙은 확장하지 않았다.
- 실제 확인: 한국어 최종 STT/NORMAL, 재질문 청취, 로컬 테스트 음 재생 및 사용자 청취,
  별도 단일 시험의 정확한 로컬 별칭 판정. 이들은 서로 다른 시험의 결과다.
- **미완료:** 실제 발화부터 재생 중 음악 중단→TTS→최종 STT까지 한 번에 연결한 통합 시험.
  통합에서 간헐적인 상태 미확인 및 OTHER_MUSIC/UNSUPPORTED가 있었고 원인은 미확정이다.
  단일 시험 성공을 간헐적 문제 해결로 표시하지 않는다.
- 최종 STT 문구를 한 번 확인한 임시 기능은 제거했고 기기의 테스트 APK도 교체했다.
  발화 원문/키/좌표를 문서·파일·Git에 저장하지 않았다.
- 외부 음악/뉴스, 안전 휴식 검색, 실제 차량 설정 변경/복원 검증은 계속 남아 있다.

기존 B 폴더는 다시 clone할 필요 없다. Codex에 다음과 같이 요청한다:

> git status로 미커밋 작업을 먼저 확인·보존하고 git fetch origin을 실행하세요.
> docs/B_HANDOFF.md의 2026-11-13 절을 읽고 origin/feature/voice-state 변경을 검토하세요.
> B 작업 브랜치에 해당 변경을 병합하되 충돌은 A/B 동작을 유지하며 해결하세요.
> reset/clean/force push 또는 main/develop 병합은 하지 마세요.
> build/unit test 후 실제 발화→NORMAL→로컬 테스트 음 재생→재생 중 상태 확인→음악 중단 완료
> →TTS→최종 STT를 검증하세요. 발화 시점을 안내하고 원문/키/좌표는 기록하지 마세요.
> 성공한 개별 시험과 아직 미완료인 전체 통합 시험을 구분하세요.

이 PC의 저장소는 `D:\chansproject`이며 아래의 PC 경로는 B가 작성한 이전 환경 기록이다.

## 먼저 볼 것

작업 브랜치는 `feature/b-integration`, 향후 PR 대상은 `develop`이다. 기준 커밋은 PR #1 병합 `17fae7d`이며 main/develop에 직접 푸시하거나 병합하지 않는다. 이번 인수인계는 B 작업 소스와 문서를 공유하는 것으로, B 전체 기능 완료 또는 PR 병합을 뜻하지 않는다.

현재 PC의 실제 저장소는 `C:\Users\User\StudioProjects\DriveMate-Pleos\DriveMate-Pleos`다. 바깥 `DriveMate-Pleos` 폴더도 별도 초기 저장소이므로 혼동하지 않는다. 다른 PC에서는 새로 받은 저장소 루트에서 작업한다.

읽을 순서:

1. `AGENTS.md`, `README.md`, `docs/PROJECT_SPEC.md`, `docs/DEVELOPMENT_BOUNDARIES.md`
2. 이 문서와 `docs/B5_VOICE_MEDIA.md` — 최신 음성/음악/내비 관측 이슈
3. 작업별 `B1_INTEGRATION.md`, `B2_PROFILES.md`, `B3_NAVIGATION.md`, `B3_REST_SEARCH.md`, `B3_PLACE_DATA.md`, `B3_PLACE_COMPARISON.md`, `B4_VEHICLE_READ.md`, `B4_VEHICLE_CONTROL.md`, `B5_MEDIA.md`
4. `docs/B_DEVELOPMENT_PLAN.md` — 최초 분석과 후속 기록 혼재. 최종 기준은 PROJECT_SPEC와 현재 코드다.

## 받는 방법과 환경

미커밋 작업이 있으면 먼저 보존하고 `git status`를 확인한다. 아래는 해당 브랜치가 로컬에 없을 때다.

```powershell
git fetch origin
git switch --track origin/feature/b-integration
```

이미 브랜치가 있으면 `git switch feature/b-integration` 후 `git pull --ff-only origin feature/b-integration`을 사용한다. 충돌을 강제 reset/clean으로 지우지 않는다. 키/프로필/SDK 바이너리는 Git에서 받는 대상이 아니다.

- 기존 단일 Android Studio app 프로젝트 유지. Gradle 9.6.0 / AGP 9.5.1 / Kotlin 2.2.10 / Compose BOM 2026.02.01.
- compile/target SDK 37, min SDK 30, source/target Java 11. 이 PC JAVA_HOME은 JDK 17이지만 Gradle daemon toolchain은 별도 해석되므로 기존 Gradle 설정을 먼저 확인한다.
- SpeechToText 3.3.1, TextToSpeech 3.3.1, NaviHelper 3.0.5, Vehicle 2.3.2. 버전을 임의로 올리지 않는다. Pleos Maven 접근 및 Android SDK 설치가 필요하다.
- 검증 기기: Pleos_Connect_v2.1.0 AVD(API 34), emulator-5554. 다른 기기/실차에서 같은 결과를 보장하지 않는다.

기본 검증:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest :app:compileReleaseKotlin --console=plain
.\tools\rest-api\Test-RestApi.ps1
.\tools\rest-api\Test-PlacesApi.ps1
.\tools\rest-api\Test-RestPlaceComparison.ps1
```

현재 키는 `.local/rest-api-keys.json`에 로컬 보관하며 Git ignore다. 새 PC에서는 `docs/B3_API_KEYS.md`와 도구 스크립트로 별도 설정한다. 키를 채팅/문서/커밋에 붙이지 않는다. 도구 오프라인 테스트는 실제 키나 네트워크 없이 실행한다. 사용자 프로필과 음악 앱 설정은 기기 noBackupFilesDir의 앱 전용 파일이며 Git에 없다. debug 테스트 음은 코드로 생성한 cache 파일이고 실제 음악 파일은 포함하지 않는다.

## 구현과 실제 검증의 범위

- B1: Compose 화면, ViewModel, DriveMateSessionOwner/Graph, foreground tick/background 취소/프로필 전환 정리, 명시적 Fake. 기본 실행은 시뮬레이션이다.
- B2: 로컬 프로필 등록/선택/수정/삭제와 장르·가수·콘텐츠 취향 저장, 원자 파일 저장, 사용자별 세션 격리.
- B3: 실제 NaviHelper 연결·조회·즐겨찾기·좌표 목적지 동의·경유지/재개 어댑터와 엄격한 원본/동의/세대 검증. 사용자가 실제 내비 안내 시작을 관측했으나 모든 변경 명령의 완료 판정/재개/경유지 전체 흐름을 실기기로 검증한 것은 아니다.
- 휴식 데이터: PC용 EX/TMAP 조회·전체 EX 카탈로그·보수적 비교 도구. EX HTTPS 헤더 필요, TMAP 사용 설정 필요. 진행 방향/접근성/합법 주차 근거가 없어 앱의 실제 안전 휴식 검색은 UNSUPPORTED다. 가까운 장소나 유사 이름을 안전 후보로 승격하지 않는다.
- B4: 실제 기어/속도/주차 브레이크/공조 전원/창문 조회 및 직접 검토·동의하는 공조 전원/창문 제어·별도 복원 경계. SDK 조회/구독과 격리된 UI/동의 검사는 실행했으나 native setter 변경·복원 검증은 하지 못했다. 좌석 이동/온도 제어/음성 자동 환기는 미구현이다.
- B5: 선택한 로컬 오디오 실제 재생, debug 테스트 음 선택, 실제 디코더 진행/포커스 손실·배경 중단/자동 재개 없음 확인. 시스템 파일 선택기는 이 Pleos 기기에서 미지원 안내를 띄우므로 debug 테스트 음을 제공했다.
- B5 사용자 설정: 공개 MediaBrowserService를 제공하는 설치 앱 검색·선택과 프로필별 기본 앱 저장. 저장만으로 자동 연결/재생하지 않는다. 미지원/미연결 기본 앱을 다른 앱/로컬 파일로 임의 대체하지 않는다.
- 외부 음악: 이 에뮬레이터에서 로컬 음악 서비스는 연결 거부, 블루투스 서비스는 연결되지만 목록 조회 실패였다. 설정 저장과 서비스 존재를 실제 곡 검색·재생 성공으로 표시하지 않는다. 뉴스 제공자는 미선정/미연결이다.
- 실제 음성: 기존 A PleosVoiceBackend/SerializedVoiceService와 DriveMateSession 공개 메서드에 B 조립만 추가했다. 실제 로컬 음악 중단→실제 TTS 완료 검증은 성공. 새 UI를 통한 실제 사용자 발화→상태 판단→음악 재생 전체 흐름은 아직 미검증이다. 자동 테스트에서 사용자 응답을 만들어 실제 동의로 사용하지 않았다.

## 방금 해결한 출발 버튼 문제 — 중요

기존 제어 controller.route는 앱이 직접 설정한 원본 경로만 발행하고 배경/재연결 시 소유권을 폐기했다. 차량 내비가 계속 안내 중이어도 음성용 route가 null이 될 수 있었다. 또 실제 SDK는 `isRouting=false`, `naviMode=ROUTE_GUIDING`, `termsAgreement=true`를 반복 반환했다. 설치 SDK 소스의 active 설명과 실제 서비스 값이 불일치한다. isRouting을 임의로 ‘경로 계산 중’이라고 재정의하지 않았다.

`ObservedVoiceRoute`를 추가해 최신 안내 모드/약관 + 유효한 목적지/양의 ETA가 함께 있을 때만 읽기 전용 음성 경로를 제공한다. 기존 getRouteStateInfo/getDestinationInfo/getWaypointInfo만 읽는다. 관측 route ID는 제어 소유권이나 동의가 아니다. 이 포트의 목적지/경유지/재개는 항상 UNSUPPORTED다. 실제 음성의 휴식 장소 검색도 UNSUPPORTED로 분리했다.

기존 B3 UI 제어의 strict active/원본/동의 검증은 유지했다. 해당 SDK 신호 불일치가 기존 변경 완료 판정에 주는 영향은 B3 후속 검증 대상이다. 제어 검증을 관측 기준으로 완화하지 않는다. 반복 조회 중 관측 ID가 매번 바뀌어 질문을 반복하지 않도록 테스트했으며 종료/만료/연결 해제/오래된 콜백은 차단한다.

최신 실제 계측은 안내 중인 기존 경로를 바꾸지 않고 화면에서 ‘실제 음성 연결 켜기’ 클릭→켜짐→경로 수신→‘음성 출발 확인’ enabled를 확인했다. 출발 버튼을 실제 누르지는 않았고 마이크도 열지 않았다.

## 다음 사람이 먼저 할 일

1. 위 기본 빌드/단위 검사를 확인하고 실제 발화 전체 흐름을 사람이 직접 시험한다. 기존 내비 안내 유지 -> DriveMate 실제 내비/차량 조회 켜기 -> 프로필 선택 -> 기본 음악 앱 설정 해제(로컬 시험일 때) -> 테스트 음 선택 -> 실제 음성 연결 켜기 -> 음성 출발 확인. LISTENING에서 ‘괜찮아요’, 모드 질문에서 ‘로컬 파일 틀어줘’. 음악 중 음성 상태 다시 확인을 눌러 재생 중단→질문 순서를 확인한다. 아직 확인하지 않은 결과를 성공으로 기록하지 않는다.
2. 실패하면 화면의 연결 준비/전환 안내, driver/voice phase, SDK 메타데이터만 수집한다. 원문 발화/키/좌표를 로그로 남기지 않는다. `VoiceModeActivationTest`는 수동 활성 경로 fixture가 있는 경우만 `-e b5ActiveRoute true`로 실행한다. 이 테스트는 버튼을 활성화까지만 확인하며 질문/청취를 하지 않는다.
3. 실제 외부 음악 앱 하나의 합법적인 설치/공개 연동·로그인·검색·재생/중단을 검증한다. 프로필별 기본 앱 선택 구조는 이미 있으므로 모든 음악 앱을 지원한다고 가정하지 않는다. 뉴스는 별도 제공자/이용 조건을 선정해야 한다.
4. EX/TMAP의 진행 방향·진입·주차 근거를 확보해 실제 안전 휴식 후보 공급을 연결한다. APK에 API 키를 넣는 방식으로 서둘러 연결하지 않는다.
5. B4는 공식 테스트 환경에서 주차 브레이크 체결 신호를 준비한 뒤 새로 구체적 동의를 받고 실제 변경·복원을 검증한다. 과거 채팅의 테스트 허용을 다른 사람/기기/대상값의 포괄 동의로 재사용하지 않는다. B4NativeAcceptanceTest는 명시적 인자에 의해 실제 SDK setter를 호출할 수 있으므로 기본 빌드/전체 계측과 혼동하지 않는다.
6. 전체 CarUxRestrictions/오디오 소유권/서비스 실패/백그라운드·프로필 변경 회귀, 좌석 지원 범위, 외부 뉴스 등을 마무리한다. A 계약 변경이 필요하면 이유·영향·재현 증거부터 구분한다.

## 알려진 제약과 보존 규칙

- A `domain/`, `voice/`, `scheduler/`, `pleos/voice/`, 공통 `contract/`는 이번 B 작업에서 수정하지 않았다. A 판단/질문/분류를 B에 중복 구현하지 않는다.
- 실제 음성은 기본 꺼짐이다. 연결을 켜기만 해서는 질문/마이크를 열지 않으며 별도 출발/상태 확인 조작이 필요하다. 배경으로 가면 꺼지고 복귀 시 자동 재개하지 않는다. 가상 graph에는 실제 미디어 포트를 주입하지 않는다.
- 모든 speak/listen 전 미디어 Stop 성공을 기다린다. 소유권 없는 외부 음악을 강제로 끄지 않으며 중단이 불명확하면 음성을 열지 않는다.
- P단/속도 0/주차 브레이크만으로 안전한 장소를 확정하지 않는다. SafeParking은 VERIFIED가 되지 않는다.
- 현재 emulator의 AAOS parking brake는 false다. Virtual Console Telltales/Parking Brake On은 표시등이며 실제 체결 근거가 아니었다. Extended Controls에 Car data 메뉴가 없고 car_service 명령도 user 빌드에서 제한됐다. root/정차 조건 완화/브레이크 setter는 적용하지 않았다.
- 온도 getter는 원본 값을 안정적으로 얻지 못했고 listener 값은 범위 밖이었다. 추정 온도 원본이나 임의 보정으로 제어를 허용하지 않는다. 창문 raw 위치값을 개방률/방향으로 가정하지 않는다.
- 조향/제동/주행 중 좌석 이동/자동 긴급 신고/외부 생성형 AI는 구현하지 않는다. 경로·차량 설정 변경과 복원은 별도 명시적 동의를 유지한다.

## 검증 기록 읽기

최신 기능 검증의 단위 테스트는 436개 실패/오류 0, lint 오류 0이다. 이것은 실제 SDK 기능 전체 완료율이 아니다. 실제 출발 버튼 활성화 계측 1개(7.169초), 앞선 실제 로컬 재생/TTS 선점/화면 회귀 8개(26.905초)는 각 단계 당시 결과다. 모든 계측을 최종 코드에서 한 번에 다시 실행한 것은 아니다. 전체 기기 계측에는 실제 SDK/수동 발화/실제 차량 setter 테스트가 섞여 있으므로 준비 없이 일괄 실행하지 않는다.

최종 푸시 전 재확인 결과는 이 문서 아래에 추가한다. `app/build/`의 상세 로그/APK/테스트 리포트는 로컬 산출물이고 Git에 포함하지 않는다. 앱 UI와 실제 사용자 음성 전체 흐름을 다시 확인한 것으로 오해하지 않는다.

### 이번 푸시 전 재확인

- 위 Gradle 기본 검증 명령 BUILD SUCCESSFUL(13초), 88개 작업 중 2개 실행/86개 up-to-date. Release Kotlin 컴파일 확인. 단위/lint 작업은 up-to-date였으며 기존 결과 436개 실패/오류 0, lint 오류 0/경고 25를 재확인했다. 단위 테스트가 이번에 새로 전부 실행됐다고 표현하지 않는다.
- PC REST 도구 오프라인 검사는 이번에 직접 실행: 14 + 40 + 18개 통과, 실제 API 요청/키 전송 없음.
- 이번 푸시 준비 중 native 계측/사용자 발화/차량 변경 테스트는 다시 실행하지 않았다.
- 커밋 후보에서 제한 바이너리·음악 파일·키 파일·개인 프로필 파일이 포함되지 않았는지 확인했다. `.local/rest-api-keys.json` 및 APK는 git check-ignore로 제외 확인. A 보호 경로와 공통 contract 변경 없음.
