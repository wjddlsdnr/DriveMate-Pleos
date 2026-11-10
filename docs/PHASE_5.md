# Phase 5 — 피로·졸음·무응답 대응 조정

2026-11-10, feature/voice-state. 개발자 A. 구현 전 대응 정책.
기존 공통 계약/Gradle/SDK/B UI·차량·내비 코드는 변경하지 않는다.

| 상태/관측 | 조정 로직 |
|---|---|
| CAUTION | 즉시 휴식 권고 → 지원되는 환기 제안 → TTS 완료 후 명시적 동의 청취 → 동의 시 VehicleController.apply. 위험 후속 확인 5분 예약 |
| WARNING | 일반 미디어·말동무 중단 요청, 휴식 권고, restStopNeeded=true. 실제 검색은 Phase 6. 후속 확인 1분 예약 |
| HIGH_RISK | 진행 중 동의 대화를 취소하고 즉시 위 중단/휴식 안내. 스케줄러 전달 시각을 기다리지 않음 |
| 첫 명확한 NoResponse | 상태 미확인 안내·재질문·안전 정차 권고, 후속 확인 1분 예약 |
| 연속 NoResponse 2회 이상 | HIGH_RISK 의심, report=UNCONFIRMED 유지, 일반 콘텐츠 중단·휴식 필요 표시 |
| Timeout/PermissionDenied/RecognitionFailure/ServiceUnavailable | 음성 확인 실패 안내와 재확인 예약. NoResponse로 세지 않음. 기존 위험 수준은 낮추지 않음 |
| 모호한 Text | 상태 재확인 안내. 기존 위험 수준 유지 |
| RECOVERY_CHECK | 회복 확인 안내, 회복 확인 2분 예약. 차량 설정 자동 복원이나 콘텐츠 자동 재개 없음 |
| NORMAL | 휴식 탐색 필요 해제. 차량/콘텐츠를 자동 재설정하지 않음 |
| RESTING | 상태 답변으로 대응 실행 안 함 |

## A 영역 경계와 공통 계약 영향

A의 `RiskStateManager : DriverStateManager`에 반복 무응답 고위험 의심 입력을 정의하고
기존 DefaultDriverStateManager가 이를 구현한다. 공통 DriverStateManager와 B의 계약은 그대로다.
위험 의심 입력은 기존 상태 관리자에서 세어 둔 명확한 연속 NoResponse가 2회 이상일 때만 수락하며
의심 후 정상 복귀도 기존 회복 확인 규칙을 따른다. 확정 졸음이나 건강 진단이 아니다.

A의 `FollowUpScheduler : CheckScheduler`에 cancelFollowUps를 추가하고 DefaultCheckScheduler가 구현한다.
회복/정상/새 상태 답변/취소에서 오래된 위험·회복 예약만 지우고 일반 주행 일정은 보존하기 위함이다.
공통 CheckScheduler 자체와 B 구현 요구사항은 변경하지 않는다.

SafetyResponseCoordinator는 외부에서 전달한 상태 질문 결과를 소비한다. 실제 마이크 루프/질문 polling,
UI 구독/실제 B Adapter의 조립은 후속 통합 범위다. 공통 VoiceService/VehicleController/MediaController/
ConversationController/CheckScheduler로만 요청한다. RestStopFinder와 NavigationController는 참조하지 않는다.

## 동의·취소·실패 정책

- 동일/역순 수신 시각은 재처리하지 않는다. 답변 시각은 주입 단조 시계 이하여야 한다.
- 새로운 상태 답변은 이전 조정 작업을 취소·정리한 뒤 처리한다. 심한 졸음은 진행 중 동의 대기를 선점한다.
- 환기 제안은 피로 에피소드당 한 번만 시도한다. 지원 실패/거절/모호한 응답/취소 때 강제로 재요청하지 않는다.
- 환기 동의 질문 결과는 운전자 컨디션 답변과 분리한다. 동의 질문의 침묵/오류로 졸음 위험을 올리지 않는다.
- 명시적 긍정 문장 전체만 동의로 처리한다. 기본 동의 없음. 제안 ID와 VEHICLE_ASSIST scope에 묶고
  제안 생성 후 60초 이내의 동의만 한 번 apply에 사용한다. 동의 청취는 10초 제한이다.
- B는 현재 제안/차량 제한/철회 여부/변경 전 설정 저장 및 수동 변경 제외를 실제로 검증해야 한다.
- `observeManualOverrides()`는 호출자 소유 coroutine에서 수집한다. 수동 변경 감지 시 진행 중 동의 작업을
  취소하고 이 조정기 수명 동안 추가 자동 보조 요청을 차단한다. 수동 변경을 위험 증거로 삼지 않는다.
- apply 취소/실패는 결과 미확정일 수 있어 자동 재시도·강제 복원하지 않는다. B의 차량 상태 재조회가 필요하다.
- 외부 서비스 실패는 snapshot의 failure로 표시하고 안전 안내를 계속 시도한다. 취소는 호출자에게 전파한다.
- 외부 요청은 기본 30초 제한, 말동무 중단은 1초 제한이다. 미디어 중단을 병렬 요청하여 그 완료를
  기다리느라 안전 발화를 미루지 않는다. UI/SDK Adapter는 내부에서 스레드를 막지 않아야 한다.
- 동의 후에도 운전자 상태가 제안 당시와 다르거나 제안 ID가 이미 소비됐으면 차량 요청을 거절한다.
- stop/close 후 자동 재청취 없음. 위험 경고 자체는 지연 질문의 최소 간격과 별개다.
- restStopNeeded는 Phase 6 인계용 상태이며 검색 성공/경유지 변경으로 표시하지 않는다.
- 차량 복원, 실제 검색/경유지 동의, 재개, 긴급 신고·조향·제동·좌석 이동은 구현하지 않는다.

## 통합·검증 경계

같은 운전자 세션의 컨디션 답변은 coordinator.onConditionAnswer를 통해 한 번만 전달한다.
일반 음성·콘텐츠 대화가 safety voice를 동시에 사용하지 않도록 상위 대화 제어기와 조립해야 한다.
observeManualOverrides는 세션 소유 scope에서 시작하고 세션 종료 시 수집을 취소한다.
DefaultCheckScheduler에는 B의 최신 활성 경로를 먼저 전달해야 하며, 경로 없이는 후속 질문 예약이 생기지 않는다.
모든 상태 전이는 메모리 내이며 개인 원문/건강 이력의 저장이나 외부 AI 호출은 추가하지 않았다.
실제 Vehicle/Media/Conversation Adapter 및 UI 연결은 이번 단계에서 구현하지 않았다.

## 변경 검토 및 검증 결과

- A 영역 추가: `domain/RiskStateManager.kt`, `domain/SafetyResponseCoordinator.kt`,
  `scheduler/FollowUpScheduler.kt`, 대응 조정 Fake 테스트와 본 문서.
- A 영역 수정: DefaultDriverStateManager의 고위험 의심 입력, DefaultCheckScheduler의 후속 예약만 취소,
  README의 단계별 상태 안내. 공통 contract/ 및 B 담당 파일은 변경하지 않았다.
- Phase 4 미커밋 결과(스케줄러/25개 테스트/정책 문서)도 이번 작업 브랜치 커밋 대상이다.
- `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain` 최종 실행 성공.
- XML 테스트 결과: **총 143개, 실패 0/오류 0**. 기존 116개 + Phase 5 신규 27개.
- 가상 시간과 Fake로 명시적 동의/거절/모호/권한/시간 초과, 제안 실패·만료·재사용 차단,
  고위험 선점, 수동 변경·취소, 서비스 실패 중 안내 유지, 회복 예약 정리와 중복 처리를 검증했다.
- Lint 오류 0/경고 24. 기존 의존성 버전 권고, 미사용 리소스 및 JNA 16KB 정렬 경고,
  lifecycle custom lint API 불일치로 일부 검사 생략 등이 포함된다. 이번 단계에서 버전을 바꾸지 않았다.
- `git diff --check` 성공. 기존 미추적 less 도움말 파일은 변경/커밋 대상에서 제외한다.

**미검증:** 실제 차량·Emulator에서 Phase 5 음성 대화/차량·미디어 요청 통합, 실차 수동 설정 변경,
실제 SDK가 취소를 처리하는 시점과 외부 요청 후 차량 상태 재조회. Fake 성공을 실제 차량 제어 성공으로 표시하지 않는다.
휴식 장소 탐색·경유지·차량 복원·자동 운전 재개·긴급 신고는 구현하지 않았다.
Phase 6에서는 restStopNeeded를 소비하되 운전자 확인 없는 경로 변경을 하지 않아야 한다.
