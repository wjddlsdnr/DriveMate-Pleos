# 주변 검색 앱 연결 — 2026-11-14

## 구현 범위

Debug 앱의 `주변 휴식 장소 · 개발 검증` 패널 → NearbyDiscoveryController →
NearbySearchConsentGate → DebugNearbyTransport → PC 중계 `/v1/nearby` 연결.
기존 A/B 공통 계약과 실제 내비 변경·차량 setter는 변경하지 않는다.

실제 내비·차량 조회 연결, 선택 프로필, foreground, P단·속도 0, 실제 음성 연결 꺼짐이 필요하다.
P단·속도 0은 안전한 주차 장소를 증명하지 않는다. 사용자가 안전한 장소에서 직접 시험한다.

토큰 입력 → 전송 내용 확인(네트워크 없음) → 위치 전송 명시적 동의 → 1회 POST.
준비 시점에 5초 이내로 수신한 위치 한 건을 고정하고 30초 동안 동의 내용을 확인한다.
위치 갱신은 고정한 좌표를 대체하지 않는다. 현재 경로 ID/revision 변경, 최신 위치 문맥 소실,
연결/허용 조건 변경, 취소 또는 동의 만료 시 전송을 차단한다.
고정 위치는 전송 시 최대 35초 미만이며 최신 위치라고 표시하지 않는다.
화면에 남은 초를 표시하고 30초 후 자동으로 동의 화면을 닫는다.
단순 만료 시에만 화면 메모리의 토큰을 유지해 ‘전송 내용 확인’으로 다시 준비할 수 있다.
전송 시작·취소·비활성화 때는 화면 토큰을 지운다.
Bearer 토큰은 화면의 일시 메모리만 사용하며 저장·로그·프로세스 복구를 하지 않는다.
입력은 비밀번호 표시 및 자동 교정 비활성이다.

## 통신과 취소

Emulator 전용 고정 주소 `http://10.0.2.2:18765/v1/nearby`를 사용한다.
Debug만 INTERNET과 해당 호스트의 cleartext 예외를 가진다. Release는 실패 반환 stub이다.
HTTP는 로컬 개발용이며 운영 서비스의 인증·TLS·배포 구현은 포함하지 않는다.
리디렉션 금지, 연결 5초/읽기 15초, 응답 최대 64KiB, 전체 코루틴 제한 20초, 자동 재시도 없음.
취소·백그라운드·프로필·음성/내비/차량 연결 전환·종료 시 대기 동의와 작업을 폐기한다.
실행 직전 경로/동의 기한/허용 상태를 다시 확인하며 반환 시 경로 ID/revision과 허용 조건을 재확인한다.
경로 변경은 실행 전 또는 결과 수신 시 확인한다. 이미 시작된 요청의 즉시 중단을 보장하지 않는다.
HttpURLConnection의 blocking I/O는 코루틴 취소만으로 즉시 끝나지 않을 수 있으며 소켓 제한 시간 이후 정리된다.
PC 중계가 이미 TMAP으로 보낸 위치는 취소로 회수되지 않는다.

## 결과 처리

DiscoveryOnly/version 1, safeCandidates 비어 있음, 최대 20건,
direction/access/parking 검증 플래그가 모두 false인 응답만 건수로 표시한다.
장소를 안전한 정차 후보로 승격하거나 음성 안내·경유지로 연결하지 않는다.
빈 결과는 0건이며 실패와 구분한다. 본 단계는 안전 휴식 안내의 완성이 아니다.

## 검증 구분

순수 Kotlin Fake 테스트: 동의 전 무전송, 중복 확인, 토큰/허용 조건/기한,
위치 변경, 취소, 지연 결과의 경로 변경, 시간 초과, 서비스 실패를 검증한다.
실제 위치를 쓰는 Emulator → PC → TMAP 요청과 패널 실동작은 아직 미검증이다.
이번 단계에서 실제 위치를 외부로 전송하지 않았다. 기존 음성/미디어 실측 결과는 기존 문서를 유지한다.

실행 결과: `:app:assembleDebug :app:testDebugUnitTest :app:compileReleaseKotlin` 성공.
단위 테스트 총 471개, 실패 0개(이번 컨트롤러 테스트 6개 포함).
경로 변경 후 busy 표시가 남는 결함을 회귀 테스트로 발견하여 컨트롤러 취소 호출을 명확히 지정해 수정했다.
`git diff --check` 오류 없음. Release APK 조립·패널 계측·실제 HTTP 검증은 실행하지 않았다.

다음 실제 시험은 사용자에게 전송 범위를 설명하고 직접 동의받은 뒤 진행한다.
PC 중계는 `-Serve -EnableProviderRequests -EnableLocationRequests`가 모두 필요하며,
세션 토큰은 `.local/relay-session-token.txt`에서 사용자가 입력한다. 키/토큰/좌표를 채팅이나 Git에 올리지 않는다.

## 실제 시험 준비 중 발견한 중계 문제

Emulator 최신 APK 설치, 앱 표시명 쉼길, 실제 내비 서비스 연결 및 차량 조회 P/속도 0을 관찰했다.
위치 전송 전 `/health` 요청에서 Dispatch/CommandNotFoundException이 발생했다.
NearbyRelay의 중첩 LocalRelay 강제 재로드가 외부 dispatcher 가용성을 깨뜨려 `-Force`를 제거했다.
서버와 같은 모듈 import 순서를 검사하는 Test-RelayModuleComposition.ps1을 추가했다.
모듈 조합 2개·LocalRelay 19개·NearbyRelay 19개 오프라인 검사 통과,
중계 재시작 후 실제 HTTP `/health`가 Ready를 반환했다. 외부 API 호출은 포함하지 않는다.
실제 위치 검색은 토큰 입력·앱 동의·검색 결과 관찰 전까지 미검증으로 유지한다.

## 동의 화면 만료 개선 검증

30초 고정 위치 동의/남은 시간/만료 후 재준비 정책으로 변경했다.
위치 갱신 후에도 준비 시점 좌표만 요청하는 테스트, 30초 경계 거부,
무전송 자동 만료 및 재준비 후 1회 전송을 추가했다.
Debug APK 조립·Release Kotlin 컴파일 성공, 단위 테스트 474개 실패 0개.
Emulator에 업데이트 설치 성공. 변경 화면의 실제 검색 동의·HTTP 응답은 아직 재검증하지 않았다.
커밋·푸시는 하지 않았다.

## 실제 검색 진단 결과 — 2026-11-14

사용자가 채팅에서 Emulator 위도·경도를 PC 중계를 통해 TMAP으로 전송하는 시험을 명시적으로 허용했다.
로컬 세션 토큰은 파일에서 읽어 값 출력/문서 저장 없이 사용했다.

- 앱에서 동의 준비와 30초 카운트다운을 관찰했다. 동의 버튼 실행 후 경로 갱신으로 차단되는 경우와 통신 실패를 관찰했다.
- 동의 실패 사유를 경로 갱신/위치 수신 지연/만료로 구분했다. 임의 정상 처리나 검증 우회는 추가하지 않았다.
- NearbyRelayDiagnosticTest 단독 실행: Emulator→PC `/health`가 인증 없는 요청에 HTTP 401을 반환, 1개 통과. 위치/키/외부 API 전송 없음.
- 읽기 전용 VoiceRouteDiagnosticTest: SDK 단독과 실제 컨트롤러에서 경로 문맥의 유지 여부를 원문/좌표/ID 값 없이 비교했다. 해당 43초 실행에서는 컨트롤러의 유효 문맥들 사이 ID/revision 변경을 관찰하지 않았다. 앱에서 간헐적으로 보이는 경로 갱신 차단 원인은 확정하지 않았다.
- NearbyLiveRequestTest는 별도 수동 opt-in(`allowLocationToTmap=one-request`)이 있는 경우만 실제 SDK 위치를 읽고 실제 DebugNearbyTransport로 검색한다. UI 동의 흐름을 시험한 것으로 취급하지 않는다. 토큰은 실행 인자의 메모리로만 전달하고 출력하지 않는다. 일반 계측 일괄 실행 금지 정책은 유지한다.
- 최초 실제 검색은 HTTP 502. 중계 제공자 단계는 ResponseSchema였고, 응답 count가 요청 최대값과 다르며 totalCount와 같고 poi 배열인 메타데이터를 확인했다.
- TMAP의 짧은 페이지에서 실제 반환 count를 허용하도록 파서를 수정했다. 기존 요청 count의 echo 형태도 허용하며, 항목 수는 전체 건수/요청 페이지 크기 기준 기대값과 반드시 일치해야 한다. 부분 누락은 계속 거부한다.
- 수정 후 NearbyLiveRequestTest 단독 실제 실행: **OK (1 test), HTTP 200, DiscoveryOnly 3건, safeCandidates 0건**. Mock 결과가 아니다. SDK→PC→TMAP→Android HTTP 응답까지의 성공이며 앱 화면에 3건 표시되는 전체 UI 흐름은 아직 미검증이다.

검증: 단위 테스트 474개 통과(별도 실제 계측과 구분), APK/계측 APK 조립 성공.
오프라인 PlacesApi 43개, LocalRelay 19개, NearbyRelay 19개, 모듈 조합/중첩 제공자 3개 검사 통과.
키 일반 검색 PageValidated도 확인했으며 키 값이나 응답 장소 정보를 저장하지 않았다.
주변 검색 파라미터는 [TMAP 공식 POI 통합 검색](https://tmap-skopenapi.readme.io/reference/장소통합검색)을 확인했다.
반환 count 형식은 이번 실제 응답의 메타데이터 관찰 결과에 근거한다.

남은 확인: 앱 화면의 경로 갱신 차단 원인 및 3건 결과 표시, 후보의 진행 방향/진입/주차 검증,
별도 사용자 동의를 받는 경유지 연결. 이번 시험에서 차량 setter와 경로 변경은 실행하지 않았다.
