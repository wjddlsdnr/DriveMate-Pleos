# DriveMate-Pleos

현대자동차그룹 Pleos Connect 차량용 앱 공모전 프로젝트.

## 소개
DriveMate는 음성으로 운전자의 상태를 확인하고, 예상 주행 시간을 구간별로 나누어 주기적으로 컨디션을 재확인하는 적응형 운전 지원 앱입니다. 피로·졸음이 보고되면 휴식을 권고하고, 지원되는 차량 기능 및 내비게이션과 연계해 안전한 정차 장소 안내를 지원합니다.

기능 기준은 [최종 기획안 v2.0](docs/PROJECT_SPEC.md) 1~16장입니다.

개발자 A의 Phase 0·1 결과는 [프로젝트 분석 및 SDK 확인](docs/PHASE_0_1.md), [A/B 개발 경계와 공통 계약](docs/DEVELOPMENT_BOUNDARIES.md)에 정리했습니다. [Phase 2](docs/PHASE_2.md)에서는 음성 실행 제어, 실제 Pleos ON_DEVICE STT/TTS Adapter 및 명시적 Mock을 추가했습니다. [실제 Emulator 검증](docs/PHASE_2_EMULATOR.md)에서 TTS 완료·동시 청취 차단·취소 및 한국어 STT 최종 결과 수신을 확인했습니다. UI 연결은 아직 구현하지 않았습니다.

개발자 A의 [Phase 3](docs/PHASE_3.md)는 순수 Kotlin 응답 분류와 상태 관리입니다. 제한된 문장 사전·회복 재확인·정차 확인 경계를 구현했으며 UI·음성 대화 흐름과의 통합은 후속 단계입니다.

[Phase 4](docs/PHASE_4.md)는 주입 시계를 사용하는 구간별 확인 스케줄러입니다. ETA 재계산·중복 방지·질문 충돌 병합·경로 종료 취소를 구현했으며 실제 내비·TTS 호출은 연결하지 않았습니다.

[Phase 5](docs/PHASE_5.md)는 피로·졸음·무응답 대응 조정기입니다. 휴식 권고, 명시적 동의 후 차량 보조 요청, 후속 확인 예약과 콘텐츠 중단 요청을 구현하고 Fake로 검증했습니다. 실제 차량·콘텐츠 SDK 및 화면 통합과 휴식 장소 검색은 후속 단계입니다.

[Phase 6](docs/PHASE_6.md)는 B의 휴식 장소 검색 요청·후보 음성 안내·명시적 동의 후 경유지 추가 요청 흐름입니다. 경로 변경 시 오래된 검색·동의를 폐기하고 중복 변경을 막으며, 실제 POI/NaviHelper·화면 통합은 포함하지 않습니다.

[Phase 7](docs/PHASE_7.md)는 정상 운전 모드·음악 명령 해석과 선호도 제안/저장 요청입니다. 위험 상태 및 사용자 전환 시 일반 콘텐츠를 차단·중단하며, 실제 미디어·프로필 저장 구현과 말동무 대화는 포함하지 않습니다.

[Phase 8](docs/PHASE_8.md)은 Gleo 지원 범위 조사와 STT/TTS 기반 제한 말동무입니다. 최대 2회 응답의 고정 대화·위험/모드/사용자/백그라운드 취소·안전 음성 소유권 인계를 구현했으며, Gleo 자유 대화·외부 생성형 AI 및 실제 UI 통합은 포함하지 않습니다.

## 개발 환경

- Android Studio, Kotlin, Android Automotive OS (AAOS)
- Pleos Connect SDK, Emulator, Virtual Console
- STT/TTS: 음성 질문·응답
- NaviHelper: 목적지 및 경유지 연동 (지원 범위 확인 필요)
- HVAC/Window/Seat: 차량 기능 연동 (권한과 차량별 지원 확인 필요)

## 구현 우선순위
1. Android Studio 앱 빌드 및 에뮬레이터 실행
2. 프로필 및 음성 질의응답
3. 운전자 상태 머신, 구간별 상태 확인
4. 휴식 경로 안내 및 타이머
5. 지원되는 차량 기능 연동
6. 선택적 콘텐츠 및 Gleo AI 연동 검토

## 협업 규칙
- `main`: 안정 버전
- `develop`: 기능 통합 브랜치
- `feature/기능명`: 각자 개발 후 Pull Request로 `develop`에 병합
- 변경마다 빌드·테스트 결과와 실제 SDK/Mock 구분을 PR에 기록
- 비밀키, 계정정보, 개인 데이터 및 재배포가 금지된 SDK 자료는 커밋 금지

## 안전 원칙
주행 중 강제 좌석 이동, 차량 강제 정차, 무응답만으로 119 자동 신고는 구현하지 않습니다. 실제 차량 기능은 공식 API·권한 확인 후 연결합니다.

> 현재 저장소는 개발 초기 단계이며, 위 기능은 구현 완료를 뜻하지 않습니다.

[Phase 9](docs/PHASE_9.md)는 안전 정차 후 휴식 타이머·최소 체크포인트 복구·회복 재확인과 명시적 동의 후 경로 재개 요청입니다. Fake 기반 구성 요소이며 실제 UI/OS 알람/Emulator 통합은 포함하지 않습니다.

[Phase 10 A 통합 및 B 인수인계](docs/PHASE_10.md)는 DriveMateSession에서 상태 확인·안전 대응·휴식 안내·타이머·동의 후 재개를 연결합니다. 가상 시간/Fake 통합 검증이며 B UI·실제 차량/내비 연동과 Emulator 통합 검증은 별도입니다.
