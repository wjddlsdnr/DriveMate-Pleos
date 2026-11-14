# 로컬 중계 주변 검색 엔드포인트 — 2026-11-14

## 범위와 실행

PowerShell 7/.NET의 JSON parser를 사용하는 개발용 POST /v1/nearby를 추가했다.
기존 GET /health와 /v1/discovery는 유지한다. 기본은 위치 요청 차단이다.

```powershell
.\tools\rest-api\Start-LocalRelay.ps1 -Serve -EnableProviderRequests -EnableLocationRequests
```

이 명령 자체는 외부 API를 호출하지 않는다. 인증되고 검증된 nearby 요청이 들어와야 호출한다.
Android UI/HTTP는 아직 연결하지 않았고, 실제 차량 위치를 전송하는 시험은 하지 않았다.

## 요청과 검증

- 세션 Bearer 인증과 X-DriveMate-Location-Consent: current-location-to-tmap 표시를 요구한다.
- 표시 헤더는 인증 클라이언트의 전송 범위 선언이며 사람이 동의했다는 서버 증명은 아니다.
  향후 앱은 NearbySearchConsentGate.consume 성공 직후에만 전송해야 한다.
- JSON 본문은 숫자 latitude/longitude/radiusKm 세 개만 받는다. 반경 5km 고정.
  중복 필드, 추가 개인정보/경로 필드, 문자열 좌표, 범위 밖/비유한 수를 거부한다.
- Content-Type application/json, 명시 Content-Length 1~1024 bytes를 요구한다.
  chunked 요청은 받지 않는다. 본문 읽기는 전체 5초 제한으로 중단한다.
- 위치는 URL 쿼리 대신 본문으로 받으며 질의 문자열은 거부한다.
- 인증/활성화/동의 표시 없는 요청은 본문을 읽거나 공급자에 전달하지 않는다.
- 기존 2초 호출 간격, 고정 HTTPS 호스트, 15초/2MB 공급자 제한, 키 비노출을 유지한다.
- 결과는 DiscoveryOnly이며 모든 방향/진입/주차 플래그 false, safeCandidates=[]를 유지한다.
- 목적지/프로필/native route ID/음성 원문을 받거나 전송하지 않는다.
- 취소/연결 끊김은 현재 동기 공급자 호출을 즉시 취소하지 못한다. 운영 중계의 비동기 취소·TLS는 미구현이다.

## 변경

- NearbyRelay.psm1: 인증·동의 범위·엄격한 JSON 검증과 기존 결과 투영 재사용.
- Start-LocalRelay.ps1: 위치 요청 별도 활성화, 제한된 본문 읽기와 엔드포인트 dispatch.
- PlacesApi.psm1: 선택적 Nearby 인수에서 기존 거리순 URI 생성기를 사용한다.
  일반 검색 기본값과 EX 카탈로그는 유지한다.
- Test-NearbyRelay.ps1: 합성 요청 전용, 실제 API 호출 없음.
- 이번 단계 Android 소스/권한/SDK/공통 계약/UI는 변경하지 않았다.

## 실행 결과

- 합성 nearby relay 검사 19개 통과.
- 기존 장소 파서 검사 40개, 일반 relay 검사 19개 통과.
- 실제 루프백 HTTP: 합성 좌표의 잘못된 반경→400, 동의 범위 표시 누락→403.
  두 요청 모두 외부 제공자 호출 없이 종료했고 서버 종료 코드 0을 확인했다.
- 실제 좌표 전송/실제 주변 검색/Emulator/UI 동의/경로 변경은 미검증이다.
- Android 변경이 없어 Gradle을 재실행하지 않았다. 앞선 Android 465개 검사와 구분한다.
- 커밋·푸시하지 않았다.

다음은 앱 개발 화면의 전송 내용 확인→동의 gate→HTTP 클라이언트를 연결하는 단계다.
실제 위치 전송 시에는 수신자와 전송할 값의 범위를 사용자에게 먼저 확인한다.
