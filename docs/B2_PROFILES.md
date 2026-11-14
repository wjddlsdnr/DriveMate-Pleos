# B2 — 로컬 프로필과 선호도 저장

작업일: 2026-11-10 (Asia/Seoul). 브랜치 `feature/b-integration`, PR 대상 `develop`.
PROJECT_SPEC.md 3장과 B_DEVELOPMENT_PLAN.md의 B2 범위를 구현했다.

## 구현

- 프로필 등록·목록·선택·이름/취향 수정·삭제를 추가했다. 등록 버튼은 ‘등록하고 선택’이며 새 사용자를 명시적으로 선택한다. 이름이 같아도 UUID가 다르면 별도 프로필이다. 이름은 인증 수단이 아니다.
- 음악 장르·가수·플레이리스트·뉴스/콘텐츠 취향·음성 안내 선호도를 저장한다. A가 결정한 최근 모드와 선호도 저장 요청도 같은 로컬 저장소에 연결했다. 음성 선호도를 기록했다고 실제 음성 엔진 설정을 적용한 것은 아니다.
- 선택한 프로필 ID와 선호도는 다음 실행 때 불러온다. 운전자 상태·원문 음성·대화·건강 이력·동의·경로·차량 변경 권한은 저장하지 않는다. 실제 차량 프로필/좌석 데이터 연동도 B2 범위가 아니다.
- 프로필이 없거나 선택하지 않았으면 가상 주행 시작을 막는다. 선택한 사용자를 삭제하면 다른 사용자를 자동 선택하지 않는다. 삭제는 확인 대화상자를 거친다.
- 프로필 등록·선택·수정·삭제 시 기존 세션의 음성/작업/polling을 정리하고 close를 완료한 뒤 저장소를 변경하고 새 세션을 만든다. 동일 프로필 수정도 현재 체험을 종료하며 화면에서 안내한다.
- 시나리오를 초기화해도 실제 로컬 프로필은 지우지 않는다. B1의 Fake 프로필은 격리된 회귀 테스트용으로 남겼다. 실제 내비·차량·음성·음악은 계속 시뮬레이션이다.
- 기존 체험의 마지막 경유지 동의 질문이 완료 뒤에도 남아 혼동되던 부분은 ‘가상 휴식 경유지 추가 완료’로 표시하도록 B UI에서 보완했다. A 대화 흐름은 바꾸지 않았다.

## 저장과 실패 처리

`LocalDriverProfileRepository`는 기존 DriverProfileRepository를 구현하며 B 전용 CRUD API와 catalog StateFlow를 제공한다. 공통 contract는 변경하지 않았다. 별도 중복 DriverProfile 모델을 만들지 않고 기존 DriverPreferences와 B 전용 ProfileSnapshot/ProfileDraft를 사용한다.

`FileProfileStorage`는 앱 내부 `application.noBackupFilesDir/profiles/profiles.bin`에 버전 1의 명시적 필드 형식으로 저장한다. Android 공식 [Auto Backup 문서](https://developer.android.com/identity/data/autobackup#Files)는 noBackupFilesDir를 백업 제외 디렉터리로 명시한다. [Context API](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir())도 확인했다. 외부 저장 공간이나 네트워크를 사용하지 않는다. Manifest/백업 규칙 파일을 바꾸지 않고 저장 위치로 백업 제외를 적용했다. 실제 OEM 기기 간 이전 동작은 별도 검증이다.

- 새 외부 라이브러리나 Gradle/SDK/권한 변경 없이 기존 코루틴·JDK 파일 API를 사용했다.
- IO dispatcher와 Mutex로 로드/전체 스냅샷 저장을 직렬화한다. 작은 프로필 수를 전제로 하며 최대 20개로 제한했다.
- 임시 파일 기록 → flush와 fd.sync → ATOMIC_MOVE/REPLACE_EXISTING → 메모리 Flow 반영 순서다. 저장 실패 시 기존 파일/선택 상태를 성공 데이터로 바꾸지 않는다. 전원 장애 전체의 파일시스템 내구성을 보장한다는 의미는 아니다.
- 파일 크기/버전/개수/문자열/선택 ID/중복 ID를 검사한다. 깨진 파일이나 알 수 없는 버전은 오류로 처리하고 자동 덮어쓰기/삭제하지 않는다. 임시 파일만 남은 경우 미커밋으로 간주한다.
- 이름은 1~60자, 취향은 항목당 최대 100자·목록당 20개다. 쉼표 입력은 공백/중복/빈 항목을 정리한다.
- 다른 사용자 ID의 A 선호도 저장은 STALE_REQUEST로 거부한다. A가 가진 이전 스냅샷으로 B에서 바꾼 이름을 되돌리지 않는다. 전체 저장은 단일 repository 인스턴스를 전제로 한다.
- 저장 실패 시 편집창과 입력 내용을 유지하며 완료로 표시하지 않는다. 손상 파일 로드 실패 시 주행/등록을 막고 재시도만 제공한다.
- 앱 삭제/데이터 삭제 시 프로필도 사라진다. 클라우드 동기화/내보내기/인증/차량 사용자 식별은 구현하지 않았다. 개인 프로필 파일은 저장소에 추가하지 않는다.

## 변경 파일과 경계

앱 Kotlin 기준 경로는 `app/src/main/java/com/example/pleosconnect/`다.

- 추가: `data/profile/ProfileStorage.kt`, `LocalDriverProfileRepository.kt`, `AppProfileRepository.kt`, `ui/profile/ProfilePanel.kt`. AppProfileRepository는 Activity를 보관하지 않고 앱 프로세스에서 파일 저장소를 하나로 공유한다.
- 변경: `MainActivity.kt`, `integration/DriveMateAppGraph.kt`, `DriveMateSessionOwner.kt`, `ui/DriveMateUiState.kt`, `DriveMateViewModel.kt`, `DriveMateScreen.kt`.
- 테스트 추가: `app/src/test/.../data/profile/LocalDriverProfileRepositoryTest.kt`, `integration/ProfileSessionIntegrationTest.kt`, `app/src/androidTest/.../ui/ProfileUiTest.kt`.
- B1 화면 테스트는 주입한 메모리 전용 ViewModel을 사용하도록 조정했다. B2 화면 테스트는 임시 cache 디렉터리의 파일 저장소를 사용하고 종료 시 해당 fixture만 정리한다. 테스트의 등록/삭제가 실제 noBackup 프로필에 적용되지 않게 했다. MainActivity 호스트의 기본 저장소는 로드만 하며 fixture 명령은 별도 ViewModel로 전달한다.
- 문서: 본 문서와 README 현재 구현 안내. 과거 B1/Phase 검증 기록은 보존했다.
- A 보호 영역 `domain/`, `voice/`, `scheduler/`, `pleos/voice/`, `mock/voice/`, `contract/` 변경 없음. main/develop push·병합·커밋·PR 생성은 하지 않았다.

## 검증

단위 테스트는 실제 파일 재로드, UUID/동명이인 분리, A 선호도 저장, 다른 사용자 저장 거부, 현재 사용자 삭제 후 무선택, 원자 교체 실패 시 기존 파일 보존, 잘못된 입력/손상 데이터, 동시 등록, 실제 A 세션과의 연결 및 음성 진행 중 사용자 변경을 검증한다.

UI 테스트는 별도 fixture로 등록·수정·선택·삭제·저장 실패 후 재시도를 검증하고 B1 정상/오류/사용자 전환/foreground 경계를 회귀 확인한다. 실제 마이크·차량 SDK의 권한 검증을 대신하지 않는다.

완료된 빌드/단위 테스트/lint와 Emulator 결과는 최종 기록에 기재한다. 초기 UI 테스트는 에뮬레이터가 headless 사용자 0의 BOOTING 상태에 머물고 운전자 사용자 10이 중지되어 Activity를 찾지 못했다. 제품 MainActivity의 직접 실행도 실패했다. 테스트 Activity를 추가해도 동일하여 해당 임시 Debug 선언은 제거했고 데이터 삭제 없는 재부팅으로 재검증한다.

### 최종 결과

- 실행: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --console=plain` → `BUILD SUCCESSFUL in 22s` (공유 저장소와 첫 화면 안내 최종 구성).
- 단위 테스트 결과 XML 합산: 366개, 실패/오류/건너뜀 0. 직전 355개에 B2 테스트 11개를 추가했다. 프로필 전환 테스트는 기존 A 음성 재생 도중 새 사용자 등록을 요청하고, 이전 음성 해제·휴식 데이터 제거·운전자 상태/경로 초기화 및 사용자별 최근 모드 분리를 확인했다.
- Lint 오류 0, 경고 25. 기존 lifecycle lint API 비호환으로 일부 lifecycle 검사가 제외되는 경고와 기존 라이브러리/버전 경고가 남아 있다. 버전을 임의로 올리지 않았다.
- 재부팅 후 `sys.boot_completed=1`, 현재 사용자 10, 사용자 0/10 모두 RUNNING_UNLOCKED를 확인했다. 동일 설치 APK의 해시도 로컬 결과와 대조했다. 앱 데이터 초기화나 시스템 앱 비활성화는 하지 않았다.
- 최종 APK/테스트 APK 설치 후 API 34 Pleos Connect v2.1.0의 emulator-5554 사용자 10에서 `ProfileUiTest,DriveMateScreenTest` 실행 → `OK (7 tests)`, 34.619초. 프로필 UI 3개와 B1 회귀 4개가 통과했다. 로그는 Git 제외 `app/build/b2-ui-tests.txt`에 있다.
- 테스트는 실제 Android 파일시스템의 임시 cache 파일로 저장·수정·삭제·재로드를 검증했다. 실제 noBackup 프로필 파일을 테스트용 데이터로 덮어쓰지 않았다. 파일 재로드는 새 repository 인스턴스로 검증했으며 실제 프로세스 강제 종료/재부팅 후 프로필 복구의 전체 UI 자동 검증은 미실시다.
- 실제 차량 프로필·안전 주차 신호·음악·내비·STT/TTS·실차·OEM 백업/기기 이전·주행 중 프로필 입력 허용 여부는 미검증이다. 주행 기능은 계속 Fake이며 앱 카테고리/운전 중 사용 정책을 충족한 제품으로 표시하지 않는다.
- 공통 계약과 A 보호 영역 변경 없음, Git 공백 검사 성공. B2에서는 Gradle/SDK 버전·Manifest·권한·백업 규칙 파일 변경 없음. 앞선 B1 미커밋 변경을 보존했다.
- 실제 앱의 초기 미선택 화면을 캡처해 가로 배치·등록 버튼·주행 시작 차단을 확인했다. 첫 로드 때 불필요한 ‘처리 완료’ 문구를 표시하지 않고, 미선택 시작 버튼은 먼저 프로필 등록/선택이 필요함을 안내하도록 보완했다. 프로필 성공 안내도 오류 색상으로 표시하지 않는다. 최종 테스트 뒤 실제 MainActivity를 열어 두었고, 사용자가 실행한 Emulator는 종료하지 않았다.

## 직접 확인 방법

1. Android Studio에서 안쪽 프로젝트를 열고 app을 다시 실행한다.
2. ‘새 프로필 등록’에서 별명과 음악/콘텐츠 취향을 입력하고 ‘등록하고 선택’을 누른다.
3. 가상 주행을 시작하거나 ‘프로필·취향 수정’으로 값을 바꾼다. 프로필 처리 후 가상 주행은 새로 시작한다.
4. 앱을 종료하고 다시 열어 프로필/선호도가 남아 있는지 확인한다. 운전자 상태는 다시 미확인부터 시작한다.
5. 두 번째 프로필을 등록한 뒤 ‘저장된 프로필 선택’으로 서로 다른 취향을 확인한다. 선택한 프로필 삭제 후에는 직접 다른 프로필을 선택해야 한다.

다음 단계는 B3 내비게이션이다. NaviHelper/휴식 장소 데이터의 실제 설치 아티팩트·권한·ETA/경유지/원래 경로 재개 지원 범위를 확인하고, 동의·경로 개정 검증을 유지하면서 Fake 내비를 교체한다. B2에서 차량/미디어 SDK를 추가하지 않았다.
