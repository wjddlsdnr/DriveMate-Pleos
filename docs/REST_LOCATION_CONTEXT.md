# 휴식 검색용 읽기 전용 위치 문맥

2026-11-14. B 내부 연결 단계. 외부 검색 전송/방향 일치/안전 후보 생성/경유지 변경 완료가 아니다.

## SDK 근거와 변경 영향

- 공식 이벤트 목록에서 onCurrentLocationInfo를 확인했다.
  https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners
- API reference의 이전 event-listeners URL은 읽을 본문이 없어 개발 가이드와
  설치 NaviHelper 3.0.5/navi-data 3.0.4의 sources.jar를 대조했다.
- getCurrentLocationInfo(): Unit, onCurrentLocationInfo(CurrentLocationInfo): Unit.
  기존 NAVI_ROUTE 권한, latitude/longitude Double, heading Double,
  routeId String, currentRouteIndex Int?를 확인했다. SDK/Manifest 변경 없음.
- heading의 단위/기준·정확도·측위 시각은 이 근거로 확인하지 못했다.
  currentRouteIndex를 도로 링크나 진입 지점으로 해석하지 않는다.
- NavigationEvent.Location/NavigationLocation/RestSearchContext는 B 내부 모델이다.
  A/B 공통 contract나 A 상태 판단·동의 동작을 바꾸지 않는다.

## 동작

사용자가 기존 실제 내비 연결을 활성화한 경우 안내 모드/약관 상태 수신 뒤
목적지/경유지와 함께 위치 조회를 요청한다. 연결하지 않은 가상 모드에서는 SDK를 호출하지 않는다.
기존 backend 세대 번호 검사로 연결 종료 뒤 콜백을 배제한다.

ObservedVoiceRoute는 최신 안내·목적지가 있을 때 받은 유효 위치를
로컬 routeId/revision과 단조 시각에 묶어 메모리에만 보관한다.
searchContext() 조회 시 위치는 최대 5초, 안내·목적지 신호는 기존 10초 유효기간을 적용한다.
미래 시각/만료/무효 좌표/비유한 heading/빈 native ID/음수 index는 제공하지 않는다.
ETA로 revision이 바뀌거나 재탐색·목적지 변경·경로 종료·연결 종료 때 새 위치를 요구한다.
SDK가 원 측위 시각이나 요청 ID를 제공하지 않으므로 수신 시각 기준이며 모든 늦은
동일 연결 콜백의 실제 측위 시점을 증명하지는 못한다.

native route ID를 로컬 ID와 동일하다고 보지 않는다. 문맥의
routeCorrespondenceVerified=false/headingUnitVerified=false는 항상 유지한다.
좌표·heading·native ID는 로그/문서/파일/프로필/HTTP에 저장·전송하지 않는다.
새 모델의 toString도 redacted를 반환한다. discovery 서버는 여전히 위치 요청을 받지 않는다.

## 실제 실행과 자동 검증

- assembleDebug/testDebugUnitTest/assembleDebugAndroidTest: BUILD SUCCESSFUL(45초),
  73개 작업 중 9개 실행/64개 up-to-date. 단위 XML 459개, 실패/오류 0.
- 관측 문맥 테스트 추가: 안내 전 위치 거부, 수신 문맥 바인딩, 위치 만료,
  ETA revision/재탐색·종료 폐기, 무효 입력, 문자열 출력 비식별 확인.
- 비식별 위치 진단 계측 추가 후 assembleDebugAndroidTest 성공(4초).
- 두 APK 설치 Success. VoiceRouteDiagnosticTest.readGuidanceCallbacks **하나만** 실제 실행:
  OK(1 test), 9.083초. 위치 callback/valid=true 세 차례 및 searchContextAvailable=true 확인.
- SDK 안내 상태는 기존처럼 isRouting=false/ROUTE_GUIDING/terms=true였다.
  읽기 관측만 허용하며 실제 변경 완료 판정은 완화하지 않았다.
- 좌표·native ID·장소 원문 미출력. 외부 HTTP·차량 setter·목적지/경유지/경로 재개 호출 없음.
- 기존 음성 수정·로컬 중계·미추적 파일 보존. 커밋·푸시하지 않았다.

## 다음 단계

1. 위치 문맥을 외부에 보낼지 사용자가 선택할 요청/동의 경계와 서버 요청 모델 설계.
2. 합성 위치로 중계 주변 검색 파라미터와 오래된 경로 결과 배제를 검증.
3. 실제 위치 전송을 별도 명시적으로 허용한 시험만 수행.
4. 공급자 경로 대응·진입·시설 주차 근거 확보 전에는 discovered POI를 안전 후보로 승격하지 않음.
