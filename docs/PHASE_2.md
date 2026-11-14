# Phase 2 — 음성 질의응답 실행 계층

작업일: 2026-11-09. 브랜치: `feature/voice-state`. 개발자 A 영역만 구현.

## 변경 이유와 경계

Phase 0·1의 공통 계약을 유지한다. `VoiceService`와 B 담당 인터페이스의 시그니처는 변경하지 않았다.
음성 SDK 도입에 필요한 공용 설정만 변경했다: `settings.gradle.kts`의 Pleos Maven,
`app/build.gradle.kts`의 STT/TTS 및 가상 시간 테스트 의존성,
Manifest의 `pleos.car.permission.STT_SERVICE` / `pleos.car.permission.TTS_SERVICE`.
compileSdk 37, targetSdk 37, minSdk 30과 기존 플러그인 버전은 유지했다.
UI·MainActivity·차량·내비·프로필 코드는 수정하지 않았다.

## 공식 문서와 실제 아티팩트 대조

| 항목 | 확인 결과 |
|---|---|
| 공식 Maven | `https://nexus-playground.pleos.ai/repository/maven-releases/` 접근 확인 |
| STT | `ai.pleos.playground:SpeechToText:3.3.1` POM/AAR 해석·다운로드 확인 |
| TTS | `ai.pleos.playground:TextToSpeech:3.3.1` AAR 해석·다운로드 확인 |
| SDK 시그니처 | AAR의 classes.jar를 JDK javap로 확인: 생성자, initialize(listener), request/speak, stop, removeListener/removeEventListener, release 및 오류 enum |
| 초기화 콜백 | 두 SDK 모두 `onSuccess()` / `onFailure(ErrorType)` |
| TTS 완료 | `onDone()`만 성공, `onStop()`은 성공이 아님 |
| STT 결과 | `onUpdated(String, Boolean)`의 completed=true 결과에서 전달. 음성 구간 종료만으로 최종 결과를 확정하지 않음 |
| 서비스 최소 버전 | 두 AAR Manifest 모두 CAAS `4.1.29` / `4001029` 명시 |
| SDK 최소 Android | 두 AAR minSdk 23; 앱 minSdk 30과 빌드 가능. 실행 호환성 보증은 아님 |
| 로컬 Emulator | API 34 Pleos 이미지 revision 1 및 AVD 설정 존재. `adb devices -l` 연결 장치 없음. CAAS 설치 버전·서비스 권한 미검증 |

SDK 바이너리는 Gradle 캐시와 gitignore된 build 아래에서만 검사했으며 저장소에 추가하지 않는다.
최초 샌드박스 DNS 실패 후 승인된 외부 실행에서 공식 POM 다운로드를 확인했다.

공식 근거:
- [STT 설치](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/importing-sdk)
- [TTS 설치](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/importing-sdk)
- [STT 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/declaring-permissions)
- [TTS 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/declaring-permissions)
- [STT 초기화](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/initialization-and-release)
- [TTS 초기화](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/initialization-and-release)
- [STT 이벤트](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners)
- [TTS 이벤트](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/event-listeners)
- [STT 오류](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/error-code)
- [ON_DEVICE 모드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro)

## 구현

| 파일/패키지 | 역할 |
|---|---|
| `voice/VoiceBackend.kt` | SDK와 독립적인 내부 실행 경계 |
| `voice/SerializedVoiceService.kt` | 기존 VoiceService 구현, 상태 관찰, 동시 요청 거절, 타임아웃, 취소·정리 및 영구 종료 |
| `pleos/voice/PleosVoiceBackend.kt` | 실제 3.3.1 호출을 사용하는 ON_DEVICE Adapter, 초기화 대기, 한국어, 오디오 포커스, 리스너 해제·release |
| `mock/voice/MockVoiceBackend.kt` | 음성 입출력 없이 미리 주어진 답변만 반환하는 명시적 Mock |
| `src/test/.../voice/` | 가상 시간 및 결과/오류 처리 테스트 |

`SerializedVoiceService.ask(prompt, timeout)`은 TTS→STT 전체를 한 작업으로 점유한다.
TTS 실패/중지/시간 초과 시 STT는 시작하지 않는다. 일반 `speak`/`listen`도 동시 실행을 거절한다.
서비스는 SDK 호출을 직접 노출하지 않으며 busy/종료/정리 실패 시 안전하게 거절한다.
기존 계약에 BUSY 결과가 없어 거절은 SERVICE_UNAVAILABLE로 전달한다.
각 작업의 취소가 호출자에게 전파되고 정리 후 반환된다. stop은 재사용 가능하고 close는 멱등적 영구 종료다.

실제 Adapter는 작업마다 새 SDK 객체를 사용하고 완료/취소/실패 시 stop→리스너 제거→release 및 포커스 반환을 수행한다.
이전 SDK 객체의 지연 콜백은 이전 작업의 deferred에만 전달되어 다음 작업을 완료시킬 수 없다.
SDK 정리 중 예외가 생기면 해당 backend는 재사용을 차단한다.
ON_DEVICE만 사용하므로 앱 등록 자격 증명이나 외부 생성형 AI API를 추가하지 않았다.
ON_DEVICE 가용성과 한국어 지원 여부는 Emulator에서 확인해야 한다. 자동 서버 폴백은 하지 않는다.

### 무응답과 오류

- 명시적 NoResponse, Timeout, PermissionDenied, RecognitionFailure, ServiceUnavailable을 유지한다.
- SDK TIMEOUT 및 앱 자체 제한 시간 초과는 Timeout이며 무응답 횟수로 바꾸지 않는다.
- 중간 텍스트는 답변으로 전달하지 않는다. 빈 최종 결과는 RecognitionFailure다. 종료 이벤트만 있고 최종 결과가 없으면 제한 시간까지 기다린 후 Timeout이다.
- 3.3.1에서 확인한 이벤트에는 명확한 무음 전용 신호가 없으므로 실제 Adapter는 NoResponse를 추정 생성하지 않는다.
- Mock에서 NoResponse를 명시적으로 주입해 상위 계층의 분리 처리를 테스트할 수 있다.
- 마이크 오류는 RecognitionFailure이며 서비스 접근 권한 거부와 구별한다.
- 원문 음성/응답과 건강 이력의 저장 및 로그를 추가하지 않았다.

## B가 연결할 때

실제: `SerializedVoiceService(PleosVoiceBackend(applicationContext))`.
Mock: `SerializedVoiceService(MockVoiceBackend(listOf(RecognitionResult.Text("괜찮아요"))))`.
둘 중 하나를 명시적으로 선택한다. 실제 실행 실패를 숨기는 자동 Mock 대체는 없다.
질문 실행은 lifecycle에 연결된 coroutine에서 `ask`로 호출하고 결과의 PlaybackFailed/Answer를 구분한다.
백그라운드·오디오 정책 변경 시 서비스 `stop()`을 호출하고 최종 소유자 종료 시 `close()`를 호출한다.
Activity를 보관하지 않으며 프로세스 재생성 시 새 인스턴스를 생성한다. 자동 청취 재개는 하지 않는다.
이번 단계에서는 B의 UI에 연결하지 않았으므로 기존 기본 화면은 그대로다.

## 실행 검증과 한계

`gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain` 실행 성공.
후속 취소/정리 테스트까지 단위 테스트 총 20개(신규 19개, 기존 산술 1개), 실패 0/오류 0을 XML 결과에서 확인했다.
테스트는 가상 시간으로 실행하며 실제 수십 초를 기다리지 않는다. SDK 서비스 자체 대신 실행 제어와 SDK 결과/오류 매핑을 검증한다.
병합 Manifest에 STT_SERVICE/TTS_SERVICE와 CAAS 최소 4.1.29 메타데이터가 포함됨을 확인했다.
최종 `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain` 성공.
Lint 오류 0개/경고 24개: 버전 갱신 권고·기존 미사용 리소스·버전 카탈로그 사용 권고 등 포함.
중요 제한은 SDK 전이 의존성 JNA 5.13.0의 x86_64 `libjnidispatch.so` 16 KB 정렬 경고이며,
16 KB 페이지 환경 배포 호환성은 확인되지 않았다. SDK/플랫폼 지원 확인 없이 전이 버전을 강제 변경하지 않았다.
기존 lifecycle lint 라이브러리의 API 불일치로 `UnsafeLifecycleWhenUsage`와
`RepeatOnLifecycleWrongUsage` 검사가 생략되었다. lint 성공이 이 두 검사를 통과했다는 뜻은 아니다.
네이티브 `libjnidispatch.so` / `libuacore.so` 심볼 strip 불가 메시지가 있었으나 APK 패키징은 성공했다.
`git diff --check` 성공. `.kotlin/` 생성 캐시는 `.gitignore`에 추가했다.
실제 Emulator/Virtual Console 마이크·스피커·권한·오디오 포커스·백그라운드 실동작은 미검증이다.
위 문장은 최초 구현 시점의 기록이다. 후속 실제 Emulator 검증과 수정 결과는 [실동작 검증 기록](PHASE_2_EMULATOR.md)을 우선 참고한다.
특히 SDK stop/release의 실제 마이크 해제 시점과 서비스 콜백 순서는 실환경에서 확인해야 한다.
SDK 호출 코드의 컴파일 성공을 실차 연동 성공으로 해석하지 않는다.

Emulator 후속 검증:
1. Pleos AVD Cold boot 후 `adb devices -l`로 연결을 확인한다.
2. `adb shell dumpsys package ai.pleos.playground.caas`에서 최소 버전 4.1.29와 대조한다.
3. 앱의 서비스 권한과 센서 개인정보/호스트 마이크 접근 설정을 확인한다.
4. B와 합의된 테스트 진입점에서 ON_DEVICE 한국어 질문/답변을 실행한다.
5. TTS 중 마이크 미사용, 종료 후 STT 시작, 중단·백그라운드·포커스 상실·마이크 거부·서비스 장애를 검증한다.
6. API 34 Emulator에서 앱 target 37 호환성과 주행 중 사용 정책을 검증한다.

상태 판단·질문 내용 자동 선택·운전자 상태 머신·구간별 스케줄·위험 대응·휴식 타이머는 Phase 3 이후 범위로 남겼다.
