# Phase 6 — 휴식 장소 검색 요청과 동의 기반 경유지

2026-11-10, feature/voice-state, 개발자 A. 구현 전 흐름과 책임 경계.
공통 contract/, Gradle/SDK, B의 UI·실제 POI 검색·NaviHelper는 변경하지 않는다.

## 흐름

1. Phase 5 안전 안내 완료 후, 상위 대화 소유자가 RestStopGuidanceCoordinator.request()를 호출한다.
   생성자에 Phase 5의 response StateFlow를 전달한다. restStopNeeded=true이고 WARNING/HIGH_RISK/
   RECOVERY_CHECK일 때만 진행한다. 고위험이라도 경로 변경에 대한 동의를 생략하지 않는다.
2. 활성 RouteProgress의 routeId/revision과 상태 관측을 검색 요청에 결합한다.
   B의 RestStopFinder.find만 호출하며 위치·지도/SDK API를 A가 직접 사용하지 않는다.
3. B는 진행 방향·접근 가능성·합법적 안전 정차 가능성을 검증하고 가까운 순서로 반환한다.
   공통 모델에 거리 필드가 없으므로 A는 가까움을 임의로 계산하지 않고 첫 후보만 제시한다.
4. “후보 이름을 휴식 경유지로 추가할까요? 기존 목적지는 유지합니다.”라는 TTS가 완료된 뒤 청취한다.
5. 명확한 긍정만 승인한다. DriverConsent.scope=REST_WAYPOINT, targetId=후보 ID,
   confirmedAtMillis=명시적 응답 시각으로 생성한다. RestStopSearch의 고유 requestId와 routeId/revision도
   함께 NavigationController.addRestWaypoint에 넘긴다. B는 제안/동의/경로/후보의 일치·유효성을 다시 검증한다.
6. 동의는 1회 사용하고 addRestWaypoint만 호출한다. setDestination/resumeRoute는 호출하지 않는다.

## 실패·취소·중복 정책

| 상황 | 처리 |
|---|---|
| 휴식 불필요/정상/RESTING | 검색 및 발화 안 함 |
| 경로 없음/도착 | 검색 및 경유지 요청 안 함 |
| 빈 검색 결과 | EMPTY, 안전 정차 권고, 경로 변경 없음 |
| 잘못된 후보 ID/이름·중복 ID | INVALID_REQUEST, 동의/경로 변경 없음 |
| 검색 서비스 실패/예외/15초 시간 초과 | FAILED, 안전 정차 권고. 실제 검색 성공으로 표시하지 않음 |
| TTS 실패/30초 시간 초과 | 청취/경유지 요청 안 함 |
| 명시적 거절/모호한 문장 | DECLINED, 다른 후보를 연속으로 강요하지 않음 |
| NoResponse | NO_RESPONSE, 경유지 변경 없음. 컨디션 무응답 횟수와 분리 |
| STT Timeout/권한/인식/서비스 오류 | FAILED, 오류 종류 유지. 동의나 졸음으로 취급하지 않음 |
| 검색 중/동의 대기 중 경로 revision 변경·종료·상태 변경 | 작업 취소·음성 정리, STALE. 오래된 후보/동의 폐기 |
| 동일 경로·동일 상태에서 중복 request | 진행 중이거나 이미 시도했으면 무시 |
| 명시적 retry=true | 새 검색·새 requestId·새 안내·새 동의. 경유지 요청을 보낸 조정기는 revision 변경 후에도 재시도 금지 |
| 경유지 요청 중 취소/시간 초과/서비스 실패 | UNCERTAIN, 자동 재시도/복원 없음. B가 실제 경로를 재조회해야 함 |

후보 유효 시간은 검색 시작부터 60초, 청취 제한은 10초다. 모두 앱 정책이며 공식 SDK 시그니처가 아니다.
검색/동의 중에는 route와 Phase 5 상태를 구독하여 즉시 취소하고, 모든 외부 대기 후에도 다시 검증한다.
경유지 요청 직전까지 최신 경로/상태/유효 시간을 확인한다. 요청 이후의 경로 변경은 해당 요청의
정상 결과일 수도 있으므로, 요청 중 원자적 경로 검증·중복 방지·실제 결과 판정은 B의 책임이다.
동의 후 addRestWaypoint가 성공을 반환한 경우만 ADDED다. 불확실한 결과를 성공으로 표시하지 않는다.

## 수명주기와 음성 소유권

상위 계층은 새 상태 질문/안전 경고를 실행하기 전에 guidance.cancel() 완료를 기다리고,
Phase 5 처리 완료 후 request()를 호출한다. 두 조정기가 같은 VoiceService를 동시에 소유하지 않게 한다.
이 순서 연결과 실제 UI 조립은 후속 통합 범위다. 자동 검색 polling이나 질문 루프는 추가하지 않는다.
cancel/close는 진행 작업 정리 후 반환하며 close는 영구 종료다. 프로세스 재생성 시 동의/진행 검색을 복원하지 않는다.
조정기 한 개는 하나의 휴식 안내 세션을 담당한다. 경유지 요청 이후의 새 안내 세션은 B가 현재 경로와
기존 휴식 경유지를 재조회·정리한 뒤 생성해야 한다. 특히 Timeout/취소 후 새 인스턴스를 무조건 생성해
재요청하면 중복 경유지가 될 수 있다. B도 requestId와 후보/현재 경로를 기준으로 중복 변경을 거부해야 한다.
좌석 이동·조향·제동·강제 정차·자동 신고·타이머·Phase 7 이후 기능은 구현하지 않는다.

## 변경·검증 결과

- 추가: `domain/RestStopGuidanceCoordinator.kt`, `src/test/.../domain/RestStopGuidanceCoordinatorTest.kt`, 본 문서.
- 수정: README의 Phase 6 안내만 추가. Phase 0~5 소스, 공통 계약, B 담당 코드 및 SDK 설정은 유지했다.
- `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain` 최종 실행 BUILD SUCCESSFUL.
- XML 결과: **총 171개 테스트, 실패 0/오류 0** (기존 143개 + Phase 6 신규 28개).
- Fake와 가상 시간으로 후보 선택·정확한 동의 범위·거절·무응답·빈 결과·잘못된 후보·권한 및 서비스 실패,
  검색/청취/재생/경유지 요청 시간 초과, 경로 재탐색·도착·취소·새 상태 관측 시 폐기,
  명시적 재시도의 새 검색/동의, 경유지 요청 후 재요청 차단과 취소 정리 중 중복 시작 차단을 확인했다.
- Lint 오류 0/경고 24. 기존 의존성 버전 권고·미사용 리소스·JNA 16KB 정렬 및 lifecycle custom lint
  일부 검사 생략 등이 포함된다. 버전을 임의 변경하지 않았다.
- `git diff --check` 성공. 기존 미추적 less 도움말 파일은 수정/커밋하지 않는다.

**미검증:** 실제 휴식 장소 검색 정확도/안전성·가까운 순서, 실제 NaviHelper의 경유지 유지/변경,
Emulator 및 실차에서 Phase 5·6 음성 소유권 인계와 취소, 실제 SDK 요청의 원자성·멱등성.
Fake 성공을 실제 검색/경유지 변경 성공으로 표시하지 않는다. B Adapter는 현재 경로와 후보/동의의
일치를 변경 직전에 검증하고 결과가 불확실하면 실제 경로를 재조회해야 한다.
