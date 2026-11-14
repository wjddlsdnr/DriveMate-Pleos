# B3 휴게소 데이터·TMAP 검색 어댑터

후속: [장소 대조·경로 근거 검사](B3_PLACE_COMPARISON.md)를 추가했다. 명시적 방향 이름의 대조와 만료/경로 바인딩 검사를 구현했으며 실제 진입 판정 공급자는 아직 미연결이다.

검증일: 2026-11-11. 이번 단계는 PC 전용 읽기 어댑터다. Android 앱에 키/외부 검색 HTTP를 넣지 않았고 화면·A 코드·공통 계약·SDK/Gradle 버전도 바꾸지 않았다. 앱의 자동 휴식 장소 검색은 여전히 미지원이다.

**최신 인증 확인:** 사용자가 TMAP 사용 설정을 켠 뒤 같은 키로 다시 요청했다. 일반 ‘휴게소’ 검색은 PageValidated, 전체 검색 결과 6,811건 중 첫 페이지 20건, 거부 0으로 성공했다. 실제 정상 JSON과 기존 파서의 페이지/좌표 필드 대조를 완료했다. 아래 INVALID_API_KEY 설명은 설정 전 기록이다. 검색어에 맞는 일반 장소도 포함될 수 있으므로 6,811건을 고속도로 휴게소 수나 안전 후보 수로 해석하지 않는다. 검색/좌표 형식 검증만으로 방향·진입·주차 검증은 충족되지 않는다.

## 도로공사 데이터 처리

`tools/rest-api/PlacesApi.psm1`에 실제 `/openapi/locationinfo/locationinfoRest` 페이지 읽기와 정규화를 구현했다. 서버는 `SUCCESS`, count/pageNo/numOfRows/pageSize/list를 반환한다. 첫 1·2페이지가 다른 데이터임을 확인했다. 요청 100건은 서버가 99건으로 줄여 반환하므로 요청 범위와 기본 페이지 크기는 최대 99건으로 제한한다.

전체 목록은 기본 최대 5페이지, 각 15초/2MB 제한이다. 각 페이지 번호·행 수·전체 건수·전체 페이지 수·예상 레코드 수를 검사한다. 읽는 도중 전체 수가 달라지거나 페이지가 누락/반복되면 완성된 목록으로 반환하지 않는다. unitCode로 중복을 검사하고 노선 필터가 있으면 결과 routeNo도 일치해야 한다. 한 페이지라도 실패하거나 일부 레코드가 유효하지 않으면 진단 집계만 남기고 Records는 비운다. 네트워크 자동 재시도와 디스크 캐시는 없다.

각 레코드는 공급자 ID·휴게소명·노선 번호/이름·원본 X/Y 숫자를 포함한다. 이름/ID의 빈 값·제어 문자·과도한 길이와 NaN/Infinity/좌표 범위 오류를 거부한다. X/Y 범위 검사는 좌표계 검증이 아니다. 도로공사 응답의 CRS를 아직 확인하지 못해 `SourceX/SourceY`, `CoordinateSystem=Unverified`로 보관하고 위도/경도로 단정하지 않는다. 휴게소명에서 방향을 추정하지 않는다.

실제 키로 3페이지, 전체 203건, 유효 형식 203건, 중복 0, 거부 0을 확인했다. `Complete`는 **카탈로그 형식·페이지 수집 완료**이며 시설 운영/안전 검증 완료가 아니다. 공급자는 이 API의 모든 시설이 실시간 진입/주차 가능하다고 증명하지 않는다.

## TMAP 검색

[TMAP 공식 장소 통합 검색 명세](https://tmap-skopenapi.readme.io/reference/장소통합검색)에서 HTTPS endpoint, version=1, UTF-8 검색어, searchType=all, page/count, WGS84GEO 요청/응답 좌표계 및 appKey 헤더를 확인했다. 키를 URL에 넣지 않는다. 기본 검색은 일반 문자열 ‘휴게소’이며 차량 위치·목적지·프로필을 전송하지 않는다. 사용자가 CLI 검색어를 지정하면 해당 검색어는 TMAP에 전송된다.

응답 어댑터는 searchPoiInfo의 전체 수·페이지·행 수와 pois.poi 목록을 검사한다. POI ID/이름/frontLat/frontLon을 별도 TMAP 모델로 정규화한다. TMAP ID를 Pleos ID로 대체하지 않는다. 빈 결과·오류·잘못된 좌표/메타데이터는 구분한다. 단일 페이지 검색만 구현했으며 전체 TMAP 결과를 모았다고 표시하지 않는다. 정상 응답 파서는 오프라인 fixture로 검증했고 실제 정상 데이터 구조와의 대조는 아직 남아 있다.

실제 일반 ‘휴게소’ 요청에 HTTP 403, error.code=`INVALID_API_KEY`가 반환됐다. 오류 유형을 `InvalidApiKey`로 표시하고 결과는 비운다. 앱 생성 시 받은 키가 해당 API/상품에서 사용 가능한지 SK open API 대시보드에서 확인해야 한다. 키 자체가 틀렸는지/상품 권한 문제인지는 이 오류만으로 확정하지 않는다. 재발급·상품 신청·계정 변경은 자동 수행하지 않았다.

## 실행

실제 내부 프로젝트 루트의 PowerShell에서:

```powershell
# 옵션이 없으면 네트워크를 호출하지 않는다.
.\tools\rest-api\Search-RestPlaces.ps1

# 도로공사 전체 카탈로그: 기본 최대 5회 요청, 현재는 3회
.\tools\rest-api\Search-RestPlaces.ps1 -ExCatalog

# TMAP 일반 장소 검색 1페이지, 1회 요청
.\tools\rest-api\Search-RestPlaces.ps1 -Tmap
```

CLI는 상태·건수만 표시하며 키·시설명·좌표·요청 URL·원문 응답·예외 내용을 출력하지 않는다. 실제 값은 메모리 내 개발용 레코드로만 제공한다. `.local/rest-api-keys.json`은 계속 Git에서 제외하고 APK/BuildConfig/assets로 복사하지 않는다. 운영 앱 연결은 키 보호를 위한 중계 서버와 위치/경로 정보의 전송 동의 구조부터 정해야 한다.

## 검증과 제한

- PowerShell 7 및 Windows PowerShell 5.1에서 기존 검사 14개 + 데이터 검사 40개 통과. 한글 검색어 처리를 위해 스크립트는 UTF-8 BOM으로 저장한다.
- 데이터 검사는 마지막 1개 레코드/빈 목록, 유효하지 않은 좌표, 서버 오류, 중복, 페이지 상한, 중간 실패, 노선 불일치, 수집 중 전체 수 변경, 오래된 페이지 번호, 누락 키, 검색어 인코딩과 키의 URL 미포함을 포함한다.
- 실제 도로공사 203건 수집 성공. 실제 TMAP 검색은 InvalidApiKey로 실패. 키·차량 개인정보는 로그/저장소에 노출하지 않았다.
- assembleDebug/testDebugUnitTest 실행 BUILD SUCCESSFUL(2초), 43개 작업 up-to-date. 기존 단위 결과 385개 실패/오류 0을 캐시 사용. Android 코드 변경이 없어 이번에 lint/에뮬레이터 계측은 재실행하지 않았다.
- 모든 레코드의 DirectionVerified/AccessVerified/ParkingVerified는 false다. 목록 조회가 안전 주차 근거가 되지 않으며 VerifiedRestStopFinder에 연결하지 않는다. 실제 경유지·주행 경로는 변경하지 않았다.

다음은 도로공사 좌표계와 노선 방향의 공식 근거 확보, Pleos 현재 안내 경로와의 진입 가능성 검증이다. 이후 운영용 중계와 B 내부 gateway를 연결하고 명시적 동의 후 경유지 요청을 검증한다.
