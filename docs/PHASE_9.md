# Phase 9 — 휴식 타이머와 회복 확인

## 구현 전 흐름과 경계

공통 계약은 변경하지 않는다. A의 DriverStateManager, SafeParkingCheck, VoiceService,
MonotonicClock, NavigationController.resumeRoute를 사용한다. B UI·차량 상태 조회·실제
내비 SDK·프로필 저장과 Phase 10 전체 통합은 수정하지 않는다.

| 입력/상황 | 처리 |
|---|---|
| 안전 정차 VERIFIED + 시간 설정 | RESTING 진입 확인 후 타이머 시작 |
| 남은 시간 / N분 연장 / 취소 | 단조 시계 계산 / 유효 범위 내 연장 / 회복 확인 진입 |
| 만료 | 먼저 알람 소비 기록, foreground에서 한 번 안내 후 RECOVERY_CHECK |
| 정상 응답 1회 | RECOVERY_CHECK 유지, 추가 질문 |
| 정상 응답 2회 | NORMAL 확인 후 별도 경로 재개 동의 질문 |
| 명시적 재개 동의 | 캡처한 동일 경로/리비전에서만 resumeRoute 요청 |
| 피로 지속 / 모호함 / 무응답 / 인식 오류 | 구분하여 기록, 자동 재개 금지 |
| background / 정차 확인 상실 | 음성 취소·정리, 자동 음성 재개 없음 |

## 시간·복구·음성 정책

- 타이머는 1~180분. 음성 명령은 숫자 또는 일~십, 이십, 삼십 분의 제한 문법이다.
- 실행 중 단조 시계 deadline을 사용한다. tick()은 상위 수명주기 소유자가 호출한다.
- 저장 데이터는 남은 밀리초와 알람 소비 여부뿐이다. 프로필·발화·컨디션·목적지 저장 없음.
- 프로세스 종료 중 경과 시간은 추정하지 않는다. 마지막 체크포인트 남은 시간을 동결하여
  복구하고, 안전 정차 재확인 후에만 계속한다. reboot/벽시계 변경에도 같은 보수적 정책이다.
- background에서 tick은 시간 계산·체크포인트만 수행한다. 만료 알람은 foreground의 명시적
  tick까지 보류한다. OS 백그라운드 정확 알람/알림 서비스는 구현하지 않는다.
- 알람은 음성 실행 전에 소비 기록한다(at-most-once). 저장 직후 crash나 TTS 실패 시
  알람이 생략될 수 있지만 복구 시 중복 재생하지 않으며 회복 확인은 여전히 필요하다.
- 모든 음성 작업은 주입된 RestVoiceOwner를 통해 수행한다. 실제 조립 시 Phase 8의
  withSafetyPriority에 연결하고, 타 안전 안내도 동일 소유권 경로로 직렬화해야 한다.
  상위 소유자는 background/사용자 전환/더 높은 우선순위 경고 전에 cancelVoice를 호출한다.
- 타이머 종료 자체로 NORMAL/경로 재개하지 않는다. 재개 요청 실패·취소 후 자동 재시도 금지.
- 이번 단계는 A 구성 요소와 Fake 검증이며 실제 UI 수명주기/Emulator 통합은 별도이다.

## 수명주기 조립과 검증 범위

`FileRestSessionStore`는 상위 소유자가 전달한 앱 전용 경로에 버전·남은 시간·알람 소비
플래그만 atomic move로 저장한다. 저장 실패는 성공으로 간주하지 않는다. 잠든 동안에도
정확한 시각에 깨우는 Android 알람/notification 구현은 포함하지 않으며 이를 보장하지 않는다.
복구 후 tick·음성 명령 호출과 실제 lifecycle 연결은 Phase 10/B 조립 시 수행해야 한다.
프로필 전환 시 cancelVoice를 호출하고 다른 사용자의 세션으로 재사용하지 않아야 한다.
정차 확인은 청취 중 100ms 간격으로 재검사하며, 상위 차량 이벤트에서도 cancelVoice를
즉시 호출해야 한다. 권한/차량 상태 조회를 A가 직접 구현하지 않는다.

재개 동의는 10초 청취 내 명시적인 긍정 응답만 인정하고, 정상 확인은 60초 이내여야 한다.
동의 중 경로 ID/리비전 변경 시 폐기한다. 요청 직후의 차량/경로 변경과 API 실행의 원자적
검증은 기존 계약에 따라 B가 담당한다. 음성 정리 실패는 foreground 복귀로 해제하지 않는다.

수정 파일: `domain/rest/RestSessionCoordinator.kt`, `domain/rest/FileRestSessionStore.kt`,
해당 단위 테스트, 이 문서와 README. 공통 계약·Gradle·Manifest·B 코드는 변경 없음.

## 실제 실행 결과

- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: BUILD SUCCESSFUL.
- 전체 312개 단위 테스트 통과, 신규 RestSessionCoordinatorTest 27개, 실패/오류 0.
- Lint 오류 0, 기존 경고 24. SDK/Gradle 버전 변경 없음.
- Fake/가상 시간: 시간 설정·조회·연장·취소, 만료 중복, 2회 회복 확인,
  무응답/시간 초과/권한 거부/인식 실패, 명시적 동의/거절/오래된 경로,
  재개 실패 중복 방지, background/정차 상실/음성 취소, TTS→STT 순서,
  프로세스 재생성 체크포인트·손상 파일·알람 소비를 검증했다.
- Emulator 실동작, 실제 내비·차량 정차 검증, 앱 UI/OS 수명주기 조립은 실행하지 않았다.
  Fake 성공을 실제 Pleos 연동 성공으로 표시하지 않는다. Phase 10 통합은 미구현.
