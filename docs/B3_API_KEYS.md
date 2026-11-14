# B3 API 키 로컬 설정

최신 후속: [휴게소 전체 목록/검색 어댑터](B3_PLACE_DATA.md). 도로공사 실제 203건의 페이지 수집을 검증했으며 TMAP 실제 호출은 INVALID_API_KEY로 실패했다. 아래 최초 연결 도구 설명의 TMAP 미구현 상태는 작성 당시 기록이다.

2026-11-11. 현재는 PC에서 키를 설정하고 공식 API 연결을 확인하는 개발 도구다. Android 앱의 실제 휴식 검색에 키를 주입하지 않으며 APK/BuildConfig/assets/res에도 포함하지 않는다. A 영역과 공통 계약·Gradle·Manifest는 변경하지 않는다.

## 키 입력

실제 Android 프로젝트 루트(gradlew.bat가 있는 내부 DriveMate-Pleos 폴더)의 `.local/rest-api-keys.json`을 텍스트 편집기로 연다. 빈 따옴표 안에 각 키를 붙여 넣고 저장한다.

```json
{
  "tmapAppKey": "TMAP에서 발급받은 AppKey",
  "exApiKey": "data.ex.co.kr에서 발급받은 인증키"
}
```

실제 파일은 빈 값으로 생성했다. 위 예시 설명문을 실제 키 대신 저장하지 않는다. 앱 ID/서비스키 이름이 아니라 키 값만 넣고 JSON 따옴표를 유지한다. 공공데이터포털용 인코딩 키로 바꾸지 않는다.

`.local/` 전체는 Git에서 제외된다. 파일은 PC에 평문으로 저장되므로 공유·화면 캡처·폴더 압축·클라우드 동기화 때 포함하지 않는다. 키 값을 채팅이나 명령줄 인수로 전달하지 않는다. 운영 배포는 서버 중계 등 별도 인증 구조가 필요하다. 다른 체크아웃에는 파일이 자동 생성되지 않으므로 같은 구조로 직접 만든다.

## 로컬 확인

프로젝트 터미널에서 실행한다. PowerShell 7 기준 검증했다.

```powershell
.\tools\rest-api\Check-RestApi.ps1
```

각 키의 존재 여부만 True/False로 출력하며 네트워크를 호출하지 않는다. 키의 유효성·API 상품 사용 권한을 확인한 결과는 아니다.

도로공사 API 실제 읽기 요청은 아래 명령을 사용한다. 한 번 실행하면 1회 요청하며 자동 재시도하지 않는다. 현재 차량 위치·목적지·프로필은 전송하지 않는다.

```powershell
.\tools\rest-api\Check-RestApi.ps1 -LiveEx
```

선택적으로 `-RouteNo '0010'`처럼 노선 코드를 줄 수 있다. 임의 URL/호스트를 입력받지 않으며 HTTPS만 사용하고 리다이렉트와 인증서 우회를 허용하지 않는다. 키가 없으면 요청하지 않는다. 15초 제한과 응답 크기 제한을 두고 키·요청 URL·원문 응답·예외 본문은 출력/저장하지 않는다.

## 확인한 API와 미확인 부분

[도로공사 공식 가이드](https://data.ex.co.kr/guidedown/openoasis_guide.pdf)의 휴게소 위치 조회 항목에서 `/openapi/locationinfo/locationinfoRest`, key/type 필수, routeNo/unitCode 선택, unitName/routeName/xValue/yValue 필드를 확인했다. PDF 전체 가져오기는 시간 초과했으며 검색 도구에서 반환한 해당 공식 문서의 내용으로 대조했다. 문서는 HTTP URL이지만 키를 평문 전송하지 않도록 도구는 HTTPS만 시도한다. 해당 호스트의 HTTPS 실응답과 필드 단위·좌표계·최상위 JSON 구조는 키 입력 후 검증해야 한다. HTTPS 실패 시 HTTP로 낮추지 않는다.

결과 `JsonReceivedSchemaUnverified`는 JSON을 받았다는 뜻이며 인증 성공/정상 장소 데이터 수신이 아니다. HTTP 200으로 반환되는 인증 오류도 있을 수 있어 정상 스키마를 실제 대조하기 전 성공으로 바꾸지 않는다. `AccessDenied`는 HTTP 401/403, `RequestFailed`는 타임아웃/TLS/네트워크 등이며 세부 오류를 노출하지 않는다.

TMAP 키 저장과 로컬 존재 확인은 구현했다. [공식 장소 API 페이지](https://openapi.sk.com/products/detail?linkMenuSeq=12) 본문이 현재 조회에서 요청/응답 명세를 노출하지 않아 통합 검색 endpoint/파라미터를 추정 구현하지 않았다. TMAP HTTP 호출·유효성 검증은 아직 미구현이다.

검색 결과를 안전한 휴식 장소로 간주하거나 자동으로 경유지를 추가하지 않는다. 진행 방향·진입 가능·합법적 주차 근거가 없는 현재 운영 검색은 여전히 UNSUPPORTED다.

## 검증

후속 접속 진단: 사용자의 Android Studio 터미널에서도 400 RequestBlocked 재현. 키 없는 요청에 명시적 `User-Agent: DriveMate-API-Probe/1.0` 및 `Accept: application/json`을 추가하자 HTTP 200 JSON으로 바뀌었다. 도구에 두 헤더를 적용한 뒤 실제 키로도 HTTP 200 JSON 수신. list 10개와 unitName/unitCode/routeName/routeNo/xValue/yValue 등의 레코드 필드를 확인했다. 키·시설명·좌표·원문은 출력하지 않았다. 어느 헤더가 단독 원인인지는 분리 검증하지 않았다. 목록은 첫 응답이며 전체 데이터 수집/페이지 처리·좌표계/방향/접근성 검증은 아직 하지 않았다. 따라서 상태는 JsonReceivedSchemaUnverified로 유지한다. 수정 후 오프라인 검사 14개 통과. Android 앱 변경은 없어 이번에 Gradle/에뮬레이터 검사를 반복하지 않았다.

키 입력 후 실제 HTTPS 조회: 두 키의 로컬 존재 확인. 도로공사 API는 HTTP 400 HTML `Request Blocked` 반환. 키 없는 동일 API와 키 발급 공개 페이지도 같은 응답이어서 인증 실패로 확정할 수 없다. 접속 환경/사이트 차단 원인 구분이 필요하며 API 인증·데이터 수신 성공으로 기록하지 않는다. HTTP 전환·인증서 우회·차단 우회는 하지 않았다. 해당 응답을 RequestBlocked로 구분하고 오프라인 회귀 검사를 추가했다(14개 통과). TMAP 키는 이 검증에서 전송하지 않았다.

`Test-RestApi.ps1`의 오프라인 검사 13개 통과: 누락 키, 로컬 상태, URL 인코딩/노선 입력, 리다이렉트/권한/HTTP 오류, 비JSON, JSON 인증 오류를 성공으로 표시하지 않음, 잘못된 설정의 값 비노출. 실제 키는 읽거나 입력하지 않았고 외부 인증 호출은 수행하지 않았다. git check-ignore로 키 파일 제외 확인.

기존 앱 회귀 확인으로 assembleDebug/testDebugUnitTest 실행: BUILD SUCCESSFUL(9초), 43개 작업 up-to-date. 기존 단위 결과 385개, 실패/오류 0이며 이번 재실행은 캐시 결과를 사용했다. 앱 화면/SDK를 변경하지 않아 에뮬레이터 계측과 lint는 이번에 재실행하지 않았다.
