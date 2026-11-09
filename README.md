# DriveMate-Pleos

현대자동차그룹 Pleos Connect 차량용 앱 공모전 프로젝트.

## 소개
DriveMate는 음성으로 운전자의 상태를 확인하고, 예상 주행 시간을 구간별로 나누어 주기적으로 컨디션을 재확인하는 적응형 운전 지원 앱입니다. 피로·졸음이 보고되면 휴식을 권고하고, 지원되는 차량 기능 및 내비게이션과 연계해 안전한 정차 장소 안내를 지원합니다.

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
