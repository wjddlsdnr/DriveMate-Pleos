# B3 장소 대조와 경로 근거 검증

이번 구현은 **장소명·명시적 방향 표기의 대조와, 앱이 공급자의 경로 검증 근거를 검사하는 단계**다. 두 공급자의 시설 ID를 확정적으로 연결하거나 현재 경로에서 실제 진입 가능성을 판정하는 운영 데이터 공급자는 아직 없다. 앱 자동 휴식 장소 검색은 미지원 상태를 유지한다.

## PC 장소 대조

RestPlaceComparison.psm1은 완전한 도로공사 목록과 검증된 TMAP 단일 페이지를 받는다. 공백과 전각 문자 형식을 정규화하고, 괄호 또는 ‘휴게소 ○○방향’으로 명시된 방향 토큰을 보존한다. ‘부산’, ‘하행’, ‘남쪽’을 같은 방향으로 추정하지 않는다. 방향이 없는 이름, 주유소 등 뒤에 다른 시설명이 붙은 이름은 대조하지 않는다.

양쪽에서 이름·방향 키가 일대일일 때만 NameDirectionAgreement를 표시한다. 여러 시설/출입점이 겹치면 Ambiguous로 남긴다. TMAP 동일 ID의 여러 결과도 확정 매칭하지 않는다. TMAP 요청에는 공식 명세의 multiPoint=Y(기본 건물 결과)를 사용하지만 중복이 사라진다고 가정하지 않는다. 공급자 ID는 EX/TMAP으로 구분하며 Pleos ID로 변환하지 않는다.

도로공사 좌표계는 아직 공식 근거를 확인하지 못했으므로 거리 기반 확정 매칭을 하지 않는다. 대조 결과의 IdentityVerified/DirectionVerified/AccessVerified/ParkingVerified는 false, CoordinateAgreement는 null이다. SafeCandidates는 항상 빈 배열이며 대조 결과를 실제 안전 후보로 넘기지 않는다. 이름·방향 표기 일치와 현재 운전자 진행 방향 일치는 서로 다른 조건이다.

```powershell
# 도로공사 전체 3페이지 + TMAP 일반 검색 1페이지의 대조 집계
.\tools\rest-api\Compare-RestPlaces.ps1

# 도로공사 첫 명시적 방향 휴게소의 시설명을 TMAP에 검색해 대조
.\tools\rest-api\Compare-RestPlaces.ps1 -FirstNamedRestStop
```

명령마다 도로공사 최대 5회 + TMAP 1회 읽기 요청을 보낸다. 첫 명시적 이름이 없으면 TMAP 요청을 생략한다. 시설명은 공공 목록에서 얻은 검색어이며 차량 위치·프로필·실제 목적지는 전송하지 않는다. 원문/키/좌표/시설명은 출력하지 않고 집계만 표시한다. 디스크 캐시는 만들지 않는다.

실제 검사 두 가지 모두 도로공사 203건, TMAP 첫 페이지 20건을 비교했으며 NameDirectionAgreement 0, Ambiguous 0, SafeCandidates 0이었다. TMAP 전체를 검색한 결과가 아니므로 ‘해당 시설 없음’이나 ‘서비스 간 매칭 불가능’으로 해석하지 않는다. 표기가 다르거나 페이지 밖에 있는 시설을 임의 유사도/가까운 거리로 연결하지 않았다.

## 앱의 경로 근거 검사

B 내부 RestRouteEvidence를 추가했다. 검증 공급자는 정확한 검색 요청 ID·로컬 경로 ID/revision·대상 NavigationPlace·양쪽 공급자 ID·검증 출처·좌표계/시설 동일성 검증 여부·단조 시계 기반 검사 시각/만료 시각을 제공해야 한다. 최대 유효기간은 60초다. 이는 증거의 유효성을 검사하는 DTO이며 이름 매칭 결과를 자동으로 증거로 바꾸는 발급자가 아니다. 네트워크에서 읽은 이름/거리/단순 boolean만으로 이 근거를 생성하지 않는다.

VerifiedRestStopFinder는 기존 방향/접근/주차 조건과 함께 이 근거를 요구한다. 근거 누락, 다른 검색/경로/revision/좌표/POI, 미래 시각, 만료, 과도한 유효기간은 후보에서 제외한다. 후보 등록 시에도 남은 유효기간을 보존하며 동의 직전 만료된 후보는 새 동의가 있어도 STALE_REQUEST로 거부한다. 여러 후보는 가장 빠른 근거 만료 시각을 공유하도록 보수적으로 처리한다.

공통 contract와 A 로직은 바꾸지 않았다. 기존 B 데이터 공급자 경계만 강화했으며 실제 운영 공급자는 여전히 null이다. 테스트의 true 검증 근거는 명시적인 fixture이며 실제 시설의 진입 가능성을 증명하지 않는다.

## 실제 경로 검증이 남는 이유

설치 NaviHelper 3.0.5/navi-data 3.0.4의 실제 시그니처·게시 소스를 대조했다. CurrentLocationInfo에는 latitude/longitude/heading/routeId/currentRouteIndex가 있고 getCurrentLocationInfo와 onCurrentLocationInfo가 있다. RouteStartInfo에는 native route ID가 없고, TripSummary의 routePoints는 경유지/목적지 정보다. 이를 전체 도로망 형상이나 합법적 진입 증거로 취급하지 않는다. [공식 이벤트 안내](https://document.pleos.ai/api-reference/connect-sdk-pleos/NaviHelper/event-listeners)와 설치 소스를 근거로 확인했으며 이번에 새 SDK 호출·위치 수집·권한·Manifest는 추가하지 않았다.

현재 로컬 경로 ID를 native routeId와 문자열로 비교하거나, heading/직선거리만으로 진입 가능하다고 판정할 수 없다. 도로공사 CRS, 실제 안내 경로와 공급자 경로의 대응, 노선/방향/진입 연결과 합법적 주차 근거가 필요하다. 확보 불가 시 안전 후보는 계속 반환하지 않는다. 따라서 **사용자가 요청한 현재 경로의 실제 방향·진입 판정은 미완료**다.

## 변경과 검증

- PC: RestPlaceComparison.psm1, Compare-RestPlaces.ps1, Test-RestPlaceComparison.ps1 및 TMAP multiPoint 설정.
- B 앱: RestRouteEvidence.kt, VerifiedRestStopFinder.kt, PleosNavigationController.kt와 근거/만료 회귀 테스트.
- 오프라인 PC 검사 14+40+18=72개 통과. 새 대조 검사 18개는 PowerShell 7/Windows PowerShell 5.1 양쪽에서 통과했다.
- 빌드·단위·lint 최종 결과는 아래 후속 기록 참조. 화면 변경이 없어 에뮬레이터 계측과 실제 경유지 변경은 수행하지 않았다.

최종 실행: assembleDebug/testDebugUnitTest/lintDebug, BUILD SUCCESSFUL(31초). 단위 테스트 391개, 실패/오류/건너뜀 0. lint 오류 0, 경고 25. 키 파일 Git 제외와 변경 파일 공백 검사 확인.

다음 개발은 자동 후보 표시가 아니라 검증 공급자가 이용할 공식 도로·진입 데이터 확보와 네이티브 경로 대응 확인이다. 키를 APK에 넣는 방식으로 PC 도구를 앱에 복사하지 않는다.
