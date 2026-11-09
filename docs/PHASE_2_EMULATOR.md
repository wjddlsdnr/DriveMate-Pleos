# Phase 2 실제 Pleos Emulator 검증

검증일: 2026-11-09 (사용자 시간대 Asia/Seoul). 브랜치 `feature/voice-state`.
Mock을 사용하지 않고 실제 `PleosVoiceBackend`와 SDK 3.3.1로 실행했다.
개발자 B의 UI·MainActivity·차량·내비 코드는 변경하지 않았다. Phase 3 미구현.

## 환경 및 진입점

- 등록된 `Pleos_Connect_v2.1.0` AVD를 cold boot, 창 없는 백그라운드 모드로 실행했다.
- 연결: `emulator-5554`, 모델 `Connect_on_x86_64_emulator`, Android API 34.
- CAAS: `ai.pleos.playground.caas`, `4.2.10-coral-aosp`, versionCode `4002010`.
- SDK의 최소 메타데이터 `4.1.29` / `4001029` 이상을 확인했다. 모든 기능 호환성을 보장하는 의미는 아니다.
- 실제 설치 앱의 STT_SERVICE/TTS_SERVICE 권한 모두 granted=true. CAAS에서 prot=normal로 정의됨.
- `app/src/androidTest/.../voice/PleosVoiceSmokeTest.kt`에 실제 SDK 전용 진입점을 추가했다.
- `voiceScenario`: probe(기본), speak, listen, ask, cancel, overlap. release APK에는 포함되지 않는다.
- 테스트 출력은 상태와 결과 종류만 기록하고 인식 텍스트는 길이만 기록한다. SDK 자체 로그는 별개이며 개인정보 보관용으로 수집하지 않는다.

재실행 예시 (ADB 절대 경로 또는 Android Studio 터미널 PATH 설정 필요):

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --console=plain
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r -e class com.example.pleosconnect.voice.PleosVoiceSmokeTest -e voiceScenario ask com.example.pleosconnect.test/androidx.test.runner.AndroidJUnitRunner
```

ask/speak/listen 시나리오의 instrumentation `OK`는 관찰 코드가 종료됐다는 뜻이다.
음성 성공 판정은 반드시 speakResult/askResult와 SDK 콜백을 확인해야 한다. Timeout을 음성 성공으로 세지 않는다.
overlap/cancel은 실제 상태 및 취소 결과를 assert한다.

## 발견한 문제와 수정

1. 최초 TTS/STT 실행에서 `unsupported locale: ko-`, ON_DEVICE_ENGINE_ERROR를 관찰했다.
   기존 Locale.KOREAN이 지역 없는 ko를 전달했기 때문이다. 두 SDK 모두 Locale.KOREA(ko-KR)로 수정했다.
   [TTS 공식 Locale 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/set-locale)와
   [STT 공식 Locale 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/set-locale)를 확인했다.
2. 호스트 마이크 연결 전에는 청취 제한 시간 초과를 관찰했다. Emulator console 도움말을 확인하고
   `adb emu avd hostmicon`으로 호스트 입력을 활성화했다.
3. 입력 활성화 뒤 음성 구간 및 중간 텍스트 이벤트와 구간 종료를 관찰했으나 최종 답변은 확인되지 않았다.
   구간 종료 즉시 실패 처리/해제를 하지 않고 completed=true의 최종 결과를 기다리도록 보완했다.
   최종 콜백이 없으면 서비스의 제한 시간으로 종료한다. 중간 텍스트를 정상 답변으로 승격하지 않는다.

## 실제 관찰 결과

| 항목 | 결과와 근거 |
|---|---|
| 한국어 TTS 처리 | 수정 후 실제 SDK `onDone` 확인. 사람이 들은 음질/내용은 별도 미확인 |
| TTS→STT 순서 | onDone 후 STT request 및 ready 확인. 최초 수정 후 ask 상태 시각: SPEAKING 280085 → LISTENING 285412ms (Emulator 단조 시계) |
| 동시 청취 차단 | overlap에서 SPEAKING 상태 중 listen이 ServiceUnavailable로 거절됨. 625715ms 기록, assertion 통과 |
| 취소 | SPEAKING 중 stop, IDLE 복귀, cancelled=true / completed=true. 최종 보완본 assertion 통과 |
| 정리 호출 | 실제 SDK 로그에서 stop, 리스너 제거, release, 서비스 callback 해제 확인 |
| 마이크 차단 | sensor_privacy enable 0 microphone 후 sensor=1/state_type=1 확인. listen은 12초 후 Timeout. PermissionDenied로 가장하지 않음. finally에서 차단 해제 |
| 실제 서비스 오류 | 최초 ko 지역 오류를 실제 엔진에서 재현. 서비스는 ServiceUnavailable을 반환했고 종료/해제 수행. 정상으로 처리하지 않음 |
| 무응답 구분 | 입력 미확인/마이크 차단 시 Timeout/RecognitionFailure를 관찰. NoResponse 또는 졸음으로 변환하지 않음 |
| 실제 한국어 발화→최종 텍스트 | 최종 보완본도 Timeout. SPEAKING 653792 → LISTENING 657836 → Timeout 672856ms. 성공 미확인 |
| 권한 거부 | 정상 권한 부여만 실제 확인. 권한 거부 분기는 기존 단위 테스트로 확인, 실제 거부 미재현 |

빌드와 테스트: 수정 후 assembleDebug, assembleDebugAndroidTest, testDebugUnitTest 모두 BUILD SUCCESSFUL.
단위 테스트 20개 통과. UI/상태 머신/위험 판단으로 범위를 확장하지 않았다.

CAAS 프로세스 강제 종료나 패키지 비활성화는 하지 않았다. 위 서비스 오류 검증은 엔진 오류 경로이며
서비스 프로세스 사망·재연결과 백그라운드 전환 검증을 대신하지 않는다.
마이크 종료의 물리적인 무음 여부와 스피커 음질, Virtual Console 화면 설정은 이 자동 관찰로 입증되지 않는다.

## 종료 상태와 남은 문제

최종 ask 결과는 gitignore된 `build/phase2-real-ask.txt`에 원문 없이 저장했다.
CAAS AudioService 기록에서 session 441의 VOICE_RECOGNITION rec start/stop을 확인했다.
sensor_privacy는 sensor=1/state_type=2(차단 해제)로 복원했으며 hostmicoff로 임시 호스트 입력 연결을 껐다.
Emulator는 후속 검증을 위해 실행 상태로 남겨 두었다.

실제 STT 최종 텍스트 미수신 원인은 확정하지 않았다. 호스트 마이크 입력 경로·입력 장치·음량,
ON_DEVICE 엔진의 최종 결과 및 CAAS/SDK 콜백 호환성을 분리해서 확인해야 한다.
관찰 로그에는 UpdateSttResult, 구간 종료, UpdateSttCompletedToAmbientAi 이벤트가 있었으나
앱 ResultListener의 completed=true 결과를 확인하지 못했다. 중간 결과나 다른 리스너의 페이로드를
검증 없이 답변으로 채택하거나 Gleo/서버 모드로 자동 우회하지 않는다.

다음 검증에서는 Emulator를 화면에 띄워 Virtual Console/Extended Controls의 호스트 마이크 설정을
직접 확인하고, 알려진 한국어 발화를 입력해 최종 ResultListener 수신 여부를 좁힌다.
현재 판정은 **음성 제어·TTS 완료·취소·오류 처리 일부 검증, 한국어 STT 종단 간 검증 미완료**다.

## 후속 재검증 — 호스트 마이크 활성화 후 최종 결과 수신

위 기록은 이전 검증 결과로 보존한다. 다음은 같은 날짜의 후속 결과다.

Emulator를 화면이 표시되는 모드로 재시작하고 Extended Controls를 열었다.
공식 설정 가이드에 따라 사용자가 Microphone의 `Enable Host Microphone Access`를 직접 켰음을 확인했다.
Virtual Console은 차량 상태 도구이며 호스트 마이크 스위치는 Extended Controls에 있다.
Console 포트 8088 포워딩은 수행했지만 브라우저 화면 제어가 중단돼 Console 화면 자체는 미검증이다.

`VoiceDiagnostic.kt`와 PleosVoiceBackend의 선택적 진단 콜백을 추가했다.
진단값은 이벤트 이름/텍스트 길이뿐이며 원문·녹음·프로필을 기록하지 않는다. 기본값은 미사용이다.
SDK가 반환한 중간 결과를 최종 답변으로 사용하거나 AmbientAi의 시스템 앱 제한 API를 우회하지 않았다.

12초 STT 단독 테스트는 READY 1회, 입력 RMS 이벤트 106회만 받고 Timeout이었다.
이는 최종 콜백 누락과 별개로 발화 시점/입력 경로를 함께 확인해야 함을 보여준다.
자동화 실행 지연 후에도 사용자가 발화할 시간을 확보하도록 **계측 테스트에서만** listen/ask 제한 시간을 40초로 늘렸다.
제품 호출자의 타임아웃이나 기존 VoiceService 계약은 변경하지 않았다.

호스트 마이크 활성화 및 READY 확인 후 사용자에게 발화를 안내한 STT 단독 실행:

| 이벤트 | Emulator 단조 시각(ms) |
|---|---:|
| STT_REQUESTED | 390591 |
| STT_READY | 390726 |
| STT_INTERIM 길이 2 | 405908 |
| STT_INTERIM 길이 4 | 406311 |
| STT_SEGMENT_ENDED | 406526 |
| STT_FINAL 길이 4 | 406550 |
| STT_RELEASED | 406559 |
| listenResult | Text(redacted,length=4), 406561 |

**실제 공개 ResultListener에서 completed=true의 최종 텍스트 수신을 확인했다.**
최종 결과가 구간 종료보다 24ms 늦게 도착했다. 따라서 이전 구현의 구간 종료 즉시 실패/해제는
정상 최종 결과를 놓칠 수 있는 앱 결함이었으며 이미 반영한 최종 콜백 대기 수정이 필요했다.
과거 모든 Timeout의 원인이 하나로 확정됐다는 뜻은 아니다. 호스트 입력 활성화와 발화 시점 확보가 함께 필요했다.
결과 기록: gitignore된 `build/phase2-stt-40s.txt`.

### 실제 TTS→STT 최종 재검증: 성공

첫 40초 ask 재시도는 READY/RMS만 있고 발화 구간 없이 Timeout이었다. 이를 성공으로 세지 않았다.
사용자가 다시 준비됐음을 확인한 후 실제 전체 흐름을 실행한 결과:

| 이벤트 | Emulator 단조 시각(ms) |
|---|---:|
| SPEAKING | 513536 |
| TTS_DONE | 517529 |
| LISTENING | 517535 |
| STT_REQUESTED | 517546 |
| STT_READY | 517763 |
| STT_INTERIM 길이 1 / 4 | 518660 / 519050 |
| STT_SEGMENT_ENDED | 519372 |
| STT_FINAL 길이 4 | 519383 |
| STT_RELEASED | 519388 |
| askResult | Text(redacted,length=4,matchesTestPhrase=true), 519392 |

**한국어 TTS→실제 사용자 발화→공개 ResultListener 최종 텍스트가 정상 완료됐고,
공백/끝 문장부호를 제외한 값이 알려진 테스트 문구 “괜찮아요”와 일치했다.**
테스트는 원문을 출력하지 않고 일치 여부만 남겼다.
최종 결과는 다시 구간 종료보다 11ms 늦었으며, 최종 결과 대기 방식의 필요성을 재확인했다.
기록: gitignore된 `build/phase2-tts-stt-confirmation.txt`.

이번 변경 파일: VoiceDiagnostic.kt, PleosVoiceBackend.kt의 선택적 메타데이터 진단,
PleosVoiceSmokeTest.kt의 진단 수집/수동 발화용 40초 대기/문구 일치 확인,
본 문서 및 README. 기존 구간 종료 대기 수정은 앞선 검증에서 이미 반영된 코드다.
수정 후 assembleDebug, assembleDebugAndroidTest, testDebugUnitTest 성공, 기존 단위 테스트 총 20개 통과.
빌드 설정·B 담당 화면/차량/내비·Phase 3는 이번 후속 작업에서 수정하지 않았다.

최신 판정: **이 Emulator에서 실제 한국어 STT 단독 및 TTS→STT 최종 결과 수신 검증 완료**.
다른 PC/서비스 버전·장시간 반복·실차 검증이나 일반 인식 정확도를 보장하는 결과는 아니다.
호스트 마이크는 사용자가 활성화한 설정을 유지한다. 앱 청취는 각 테스트 후 종료한다.
