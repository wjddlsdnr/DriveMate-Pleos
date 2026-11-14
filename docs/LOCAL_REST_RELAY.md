# PC 로컬 휴식 장소 개발 중계

2026-11-14. 운영 서버나 앱 안전 검색 완성이 아닌 PC 개발 도구다.
Android 앱·A/B 계약·차량/내비 SDK·기존 음성 수정을 바꾸지 않는다.

## 실행

저장소 PowerShell에서:

```powershell
# 서버/네트워크 실행 없음
.\tools\rest-api\Start-LocalRelay.ps1
# 루프백 서버만 시작, 외부 제공자는 꺼짐
.\tools\rest-api\Start-LocalRelay.ps1 -Serve
# 별도 선택: 기존 로컬 키로 일반 '휴게소' TMAP 검색 1페이지
.\tools\rest-api\Start-LocalRelay.ps1 -Serve -EnableProviderRequests
```

127.0.0.1:18765에만 바인딩한다. 모든 엔드포인트는 세션마다 새로 생성한 Bearer 토큰을 요구한다.
토큰은 Git 제외 .local/relay-session-token.txt에만 기록하며 종료 시 같은 세션 토큰만 제거한다.
키는 기존 .local/rest-api-keys.json에서 서버 측으로만 읽는다. 토큰/키를 APK나 문서에 복사하지 않는다.
다른 로컬 프로세스에 대한 OS 수준 격리나 운영용 인증 서버를 제공하는 것은 아니다.

## HTTP 경계

- GET /health: Ready와 providerEnabled만 반환.
- GET /v1/discovery: 기본 ProviderDisabled. 명시적으로 제공자를 켠 경우에만 기존
  TMAP 모듈의 고정 일반 검색어/20개 첫 페이지를 요청한다.
- discovery는 장소 발견 결과다. 공급자의 필드 전체를 반환하지 않고 ID/이름/WGS84 좌표만
  재검사해 투영한다. direction/access/parkingVerified=false, safeCandidates=[]를 강제한다.
- 질의 문자열/요청 본문/GET 이외 메서드와 임의 경로는 거부한다.
  차량 위치·실제 목적지·자유 검색어·프로필은 받거나 외부에 전송하지 않는다.
- 공급자 오류/예외 본문은 ProviderUnavailable로 축약한다. 키/URL/원문 응답/요청 로그 없음.
- 외부 호출은 최대 2초 간격, 순차 처리, 기존 공급자 15초/2MB 제한과 redirect 차단을 재사용한다.
  기본 최대 60개 요청 후 종료하며 Ctrl+C로 중단할 수 있다.
- 클라이언트 연결이 끊겨도 이미 시작된 동기 외부 HTTP가 즉시 취소되는 기능은 없다.
  해당 요청은 기존 시간 제한으로 끝나며 응답 쓰기 실패는 숨기고 연결을 닫는다.
  Android 취소/운영 비동기 중계·TLS·사용자별 인증·배포는 후속 범위다.

## 검증

- Test-LocalRelay.ps1: 실제 실행 19개 오프라인 검사 통과.
  인증/메서드/쿼리/경로/기본 비활성/공급자 실패/투영/안전 플래그 초기화/예외 비노출 검증.
- 실제 HttpListener 루프백 서버를 제공자 비활성 상태로 열고 인증된 health/discovery 2회 성공.
  MaxRequests=2 종료와 종료 코드 0 확인. 샌드박스에서는 바인딩 실패해 승인된 실행으로 재검증했다.
- 외부 API 호출·실제 키 읽기·차량 위치 전송·앱 설치/Emulator/경로 변경 없음.
- Android 소스 변경이 없어 이번 도구 단계에서 Gradle은 다시 실행하지 않았다.
  기존 음성 단계의 455개 테스트와 구분한다. 커밋·푸시하지 않았다.

## 다음 연결

1. 제공자 명시 활성의 실제 일반 검색을 테스트하고 상태/건수만 보고한다.
2. 운영 공급자의 시설 동일성·방향·진입·주차 근거 확보 후 검증 경계를 설계한다.
3. 그 뒤 Android 개발 클라이언트와 사용자 위치 전송 선택을 연결한다.
   개발 서버에 연결했다는 이유로 현재 미확인 후보를 안전 후보로 반환하지 않는다.

후속 HTTP 회귀: health와 본문 거부(400/BodyNotAllowed)도 실제 루프백에서 성공했고 서버 종료 코드 0이었다.
PowerShell 7에서 19개 검사를 재확인했다. Windows PowerShell 5.1 실행은 PC 실행 정책에 차단되어
미검증으로 남겼으며 실행 정책은 변경하지 않았다. UTF-8 BOM으로 한글 검색어 인코딩을 보존했다.
토큰 파일 Git 제외와 서버 종료 후 제거도 확인했다.

## 명시 활성 실제 연결 시도

제공자 활성 루프백 중계를 MaxRequests=1로 실행하고 discovery를 1회 요청했다.
HTTP 502/ProviderUnavailable, 발견 0/안전 후보 0. 서버 종료 코드 0을 확인했다.
후속 키 존재 검사에서 두 설정 모두 false였고 지정 .local/rest-api-keys.json 파일이 없음을 확인했다.
기존 어댑터는 키 누락이면 외부 HTTP 전송 전에 반환하므로 이번 요청을 TMAP 서버 응답 실패로 해석하지 않는다.
실제 외부 인증/검색 성공은 미검증이다. 기존 키 파일이 Git 제외여서 이 체크아웃에 없는 상태다.
덮어쓰기 없이 빈 로컬 JSON만 생성하고 Git 제외를 확인했다. 키는 사용자 PC에서 직접 입력해야 한다.

## 키 설정 후 실제 제공자 검색 검증

사용자가 PC 키 파일을 직접 입력했다. 존재 여부만 TMAP/EX true를 확인하고 값은 출력하지 않았다.
기존 TMAP 모듈 직접 호출: PageValidated/20건. 실제 결과의 중계 투영: 200/DiscoveryOnly/20건.
초기 MaxRequests=1 HTTP 전달은 클라이언트 IOException으로 실패했다.
서버를 두 요청 동안 유지한 대조에서는 discovery 200/20건과 후속 health Ready를 모두 수신했다.
응답을 동기 Close(byte[],false)로 마무리하는 것만으로는 단일 요청 종료 문제를 해결하지 못했다.
KeepAlive=false와 마지막 응답 후 1초 전송 유예를 추가한 뒤 MaxRequests=1 재시험에서
HTTP 200/DiscoveryOnly/20건/safeCandidates=0 및 서버 종료 코드 0을 확인했다.
이는 관찰된 로컬 개발 환경의 종료 보완이며 모든 부하에서 전달 완료를 보증하는 운영 종료 설계는 아니다.
예외 진단은 고정 단계와 예외 클래스명만 표시하며 예외 메시지/키/원문/좌표는 표시하지 않는다.

외부에 전달한 검색어는 일반 휴게소이며 차량 위치·목적지·프로필을 보내지 않았다.
도로공사 조회는 이번에 실행하지 않았다. 안전 후보는 계속 0이며 실제 가까운 장소나 경유지 성공이 아니다.
기존 오프라인 중계 19개를 재실행해 통과했다. Android 코드 변경/Gradle 재실행/Emulator 검증 없음.
커밋·푸시하지 않았다. 다음은 위치/경로 문맥과 후보 검증 근거의 연결 설계다.
