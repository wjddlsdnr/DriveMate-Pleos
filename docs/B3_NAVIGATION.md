# B3 실제 내비 연결

2026-11-11 후속: [휴식 검색 서비스 후보와 연결 준비](B3_REST_SEARCH.md). 검색 시간 제한·오류/취소·경로 변경 결과 배제와 실제 내비/Fake 검색 혼합 방지를 보강했다. 운영 검색 공급자는 아직 미연결이다.

후속 검증: `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain` 실행, BUILD SUCCESSFUL(2분 44초). 단위 테스트 385개, 실패/오류/건너뜀 0. 이번 변경은 화면/권한/SDK를 바꾸지 않아 에뮬레이터 계측은 재실행하지 않았다. 아래 12개 계측 결과는 11월 10일 실행 기록이다.

## 현재 범위

실제 SDK 연결·상태·즐겨찾기 조회, 좌표 목적지 설정 화면, 명시적 동의와 경로 상태 연결을 구현했다. **자동 휴식 장소 검색은 안전한 장소 데이터 공급자가 없어 미지원이다. 실제 목적지 변경·경유지 추가·재개의 차량 동작 검증도 남아 있어 B3 전체 완료는 아니다.**

기본 실행은 B1 시뮬레이션이다. 실제 내비 모드에서는 미리 정한 음성 응답·동의를 실제 명령에 사용하지 않으며 가상 운전자 체험과 자동 상태 질문을 차단한다. 실제 음성 연결은 B6 범위다. DriveMateSession을 유지하고 공통 계약과 A 보호 영역은 수정하지 않았다. B2 로컬 프로필 저장도 유지한다.

## SDK 근거

- NaviHelper `ai.pleos.playground:NaviHelper:3.0.5`, 전이 의존성 navi-data `3.0.4`. 기존 Gradle·Android SDK 버전 유지.
- 공식 문서와 설치 AAR/JAR 시그니처 및 게시된 데이터 소스 대조. ETA는 초 단위이며 앱 경계에서 분으로 변환.
- 권한: `pleos.car.permission.NAVI_ROUTE`, `pleos.car.permission.NAVI_ROUTE_SEARCH`. 커스텀 지도 권한 미추가.
- AAR 요구 최소 서비스 2.0.1/code11. 에뮬레이터 실제 내비 서비스 3.0.4/code55, 두 권한 허용 확인.
- initialize/addListener/getRouteStateInfo/getDestinationInfo/getWaypointInfo/getBookmarkInfo/requestRoute/addWaypoint/removeListener/release의 실제 시그니처 사용. 별도 resume API를 가정하지 않음.
- SDK 바이너리와 검사용 소스는 무시되는 app/build 아래에만 보관하며 커밋 대상에서 제외.

공식 근거: [소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro), [SDK 추가](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk), [초기화/해제](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release), [이벤트](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners), [목적지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations), [경로](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/routes), [경유지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/waypoint-settings), [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions).

## 동작과 제한

사용자가 실제 연결을 켰을 때만 SDK에 연결한다. 프로필 선택·전경 상태·연결·최근 상태 수신·네이티브 약관 동의가 충족되어야 변경을 요청한다. 화면 확인에서 발급한 일회성 동의를 대상·경로 revision·30초 유효기간에 묶는다. 가상 응답과 재사용 동의는 실제 명령 권한이 없다.

목적지는 차량 내비 즐겨찾기 또는 직접 입력한 좌표로 지정한다. 즐겨찾기는 현재 Android 차량 사용자의 내비 데이터이며 DriveMate 프로필별 저장 목록이 아니다. 조회 결과는 메모리에만 두고 사용자 전환·연결 종료 때 비운다. 목록 선택 후에도 대상 확인과 별도 동의를 받는다. 이름 검색·주소 지오코딩은 제공하지 않는다.

void 호출 반환만으로 성공을 표시하지 않는다. 경로 시작·탐색 완료 후 활성 상태, 목적지 좌표/제공된 POI 식별자, 양수 ETA와 예상 경유지 목록을 확인한다. SDK 콜백에는 요청 ID가 없어 다른 내비 클라이언트와의 원자적 독점을 보장하지 않는다. 변경 요청 20초 시간 초과·오류·취소 시 소유권을 버리고 재연결을 요구한다. 늦은 콜백은 세대 번호로 배제하며 조회에도 시간 제한을 둔다.

기존 네이티브 경로를 앱 경로로 자동 채택하지 않는다. 직접 설정하고 확인한 경로만 로컬 ID로 소유하며 외부 목적지/경유지 변경 시 폐기한다. 정상 ETA 감소와 큰 변화·재탐색을 구분한다. 배경 전환·사용자 변경·연결 해제는 앱 권한만 버리며 네이티브 안내를 자동 취소하지 않는다.

재개는 검증한 원래 목적지를 requestRoute로 다시 요청한다. 같은 소유 경로이며 남은 경유지가 없음을 확인한 경우에만 별도 동의를 받는다. 경유지 포함 원래 경로 복원이나 배경 전환 뒤 자동 복원은 지원하지 않는다.

VerifiedRestStopFinder는 방향 일치·접근 가능·합법적 주차 근거가 모두 있는 공급자 결과만 받는다. 현재 SDK에는 해당 검색/안전 근거가 없고 운영 공급자는 연결하지 않았으므로 UNSUPPORTED다. 즐겨찾기를 안전 휴식 장소로 간주하거나 Fake 후보를 실제 경로에 넣지 않는다. 향후 공급자 연결 후 후보 유효기간·경로 revision·별도 동의로 실제 경유지 흐름을 검증해야 한다. 안전 주차는 여전히 UNKNOWN이다.

## 변경 파일

- pleos/navigation/: 실제 SDK backend, 순수 내비 controller, UI 동의 gate, 검증된 휴식 검색 경계 및 모델.
- integration/: 실제 연결 수명주기, 세션 포트 주입, 전환 시 종료 순서.
- ui/navigation/NavigationPanel.kt, UI 상태/ViewModel/화면, MainActivity: 모드·목적지·즐겨찾기·동의 화면.
- Gradle catalog/app 의존성 및 Manifest: 검증한 SDK와 두 권한 추가.
- 단위/화면/읽기 전용 SDK 계측 테스트.

## 검증 — 2026-11-10

- assembleDebug, testDebugUnitTest, lintDebug, assembleDebugAndroidTest 실행.
- 단위 테스트 382개, 실패 0. 동의 위조/만료/재사용, 오래된 상태/콜백, 시간 초과, 사용자 전환, 취소, 경로 일치, ETA, 안전 후보, 즐겨찾기 수명 검증 포함.
- lint 오류 0, 경고 25.
- 에뮬레이터 계측 12개 통과(55.797초): B1/B2 회귀, 내비 화면 동의/취소/입력, 실제 SDK 읽기 검증.
- 실제 SDK 연결 성공, RouteState 콜백 1회, 즐겨찾기 조회 성공(0개). 기존 경로를 채택하지 않고 해제 확인. 비활성 경로여서 목적지/경유지 콜백은 0회이며 변경 성공 증거는 아니다.
- 실제 경로 변경·경유지·재개·안전 장소 검색·실차·실제 음성/차량 연계는 미검증 또는 미구현. 화면 테스트는 격리된 fixture이며 실제 경로를 변경하지 않았다.

## 직접 테스트 및 후속 작업

1. 앱에서 프로필을 등록하거나 선택한다.
2. ‘실제 내비 연결하기’를 켜고 연결·상태 확인을 기다린다. 약관 안내가 나오면 네이티브 내비에서 먼저 처리한다.
3. ‘차량 내비 즐겨찾기 불러오기’를 누른다. 비어 있으면 네이티브 내비에 직접 저장하거나 개발용 좌표 입력을 사용한다.
4. 목적지를 선택하고 확인 화면의 대상·좌표를 확인한다. 실제 안내를 바꿀 의사가 있을 때만 동의한다. 취소 시 변경하지 않아야 한다.
5. 네이티브 안내와 앱의 목적지·남은 시간을 비교한다. 오류 뒤 자동 재시도하지 않는지 확인한다.

다음은 안전한 휴식 장소 데이터 공급 방법 확정과 사용자가 직접 동의한 실제 목적지 변경 검증이다. 이를 해결하기 전 자동 휴식 경유지 기능을 완료로 표시하지 않는다.
