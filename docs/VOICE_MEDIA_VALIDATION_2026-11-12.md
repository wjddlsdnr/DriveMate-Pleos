# 실제 음성·로컬 미디어 통합 검증 — 2026-11-12

대상: e9d9679, D:\chansproject, feature/voice-state. 이번 작업은 검증이며 A/B 기능 확장이나
차량 setter 실행을 포함하지 않는다. B_HANDOFF.md와 B5_VOICE_MEDIA.md를 기준으로 진행한다.

## 현재 PC 자동 검증

- assembleDebug / testDebugUnitTest / lintDebug / assembleDebugAndroidTest: BUILD SUCCESSFUL,
  6분 44초, 82개 작업 실제 실행.
- XML 결과: 단위 테스트 436개, 실패/오류 0. Lint 오류 0 / 경고 25.
- 최신 Debug 앱과 계측 APK를 emulator-5554에 데이터 보존 업데이트 설치.
- 지정 계측만 실행: LocalAudioPlayerTest, VoiceMediaPreemptionTest.
  OK (2 tests), 9.418초. 실제 디코더 진행·background/포커스 손실 중단·자동 재개 없음과
  실제 로컬 플레이어 해제 후 Pleos TTS 완료를 확인했다. microphoneRequests=0.
- 전체 계측과 B4NativeAcceptanceTest는 실행하지 않았다.

## 환경 및 직접 관찰

- Pleos_Connect_v2.1.0 / API 34 장치. CAAS 4.2.10-coral-aosp, 내비 서비스 3.0.4.
- STT_SERVICE/TTS_SERVICE granted=true. Android 오디오 마이크 mute는 false.
- Extended Controls의 Enable Host Microphone Access가 이미 켜져 있음을 직접 관찰.
  개인정보 차단 설정은 변경하지 않았다.
- 앱의 차량 조회 과정에서 Android 위치 권한 창이 나타나 사용자가 직접 허용했다.
- 차량 읽기 전용 조회: P, 0.0 km/h. 주차 브레이크 해제, 안전 주차 미확인.
  안전 조건을 완화하거나 차량 설정/브레이크를 변경하지 않았다.
- 시험 준비 중 Pleos 하드웨어 서비스 SIGABRT 및 system_server IllegalStateException
  (Invalid @IntRange: -16), 연쇄 DeadSystemException/시스템 UI 오류를 관찰했다.
  DriveMate가 원인이라는 결론은 내리지 않았다.
- 기존 에뮬레이터만 종료하고 -no-snapshot-load -no-snapshot-save로 재시작했다.
  wipe-data/root/설정 완화는 하지 않았다. 콜드 부팅 후 앱·차량 조회·내비 연결 화면 복구.
- 로컬 시험용 숫자 별명 프로필을 UI에서 생성·선택했다. 개인정보/키는 기록하지 않는다.
- 내비 서비스 연결됨, 목적지 없음/ETA 미확인을 UI에서 관찰했다.
  사용자가 내비 앱에서 시험 경로를 준비하도록 요청했다. 임의로 경로를 만들지 않았다.

## 실제 발화 전체 흐름

진행 중. 아직 실제 사용자 발화 시험은 시작하지 않았다.
활성 내비 경로·테스트 음·실제 음성 연결 준비 후,
실제 최종 STT→NORMAL→로컬 재생→질문 전 중단을 별도로 검증한다.
위 자동 테스트 통과를 전체 수동 발화 성공으로 간주하지 않는다.
원문 발화·좌표·API 키는 로그/문서에 저장하지 않는다.

## 콜드 부팅 후 추가 관찰

사용자가 에뮬레이터 내비에서 시험 경로 안내를 시작한 것을 화면으로 확인했다.
목적지/좌표를 이 기록에 남기지 않았고 기존 안내를 취소·변경하지 않았다.
DriveMate로 복귀해 선택한 로컬 테스트 음과 프로필을 유지하고 실제 음성 연결을 켰다.
‘연결 준비 완료’와 활성화된 ‘음성 출발 확인’을 확인했다.
실제 마이크 청취는 아직 시작하지 않았으며 사용자의 발화 준비 응답을 기다리고 있다.

## 첫 수동 발화 시험 — 부분 확인

실제 출발 확인 버튼을 눌러 SPEAKING→LISTENING→IDLE 전환을 화면에서 관찰했다.
이후 상태 카드에 ‘실제 음성 응답 / 정상 응답’을 확인했다. 실제 graph는 최종 completed=true
결과만 A 상태 분류로 전달하므로 최종 인식 결과를 통한 NORMAL 전환으로 판단한다.
이번 UI 시험에는 STT_FINAL 메타데이터 수집 hook가 연결되지 않아 callback 시각/문구 일치는
별도로 확인하지 않았다. 중간 결과를 최종 답변으로 대체하지 않았다.

다만 선택된 모드가 COMPANION이어서 이번 시도에서 로컬 오디오 재생 성공은 확인하지 못했다.
사용자 발화 타이밍/의도 확인 전에는 원인을 확정하지 않는다. 원문 인식 결과는 기록하지 않았다.

## 사용자 확인과 두 번째 시도

사용자는 첫 시도에서 말동무를 선택했다고 확인했다. 첫 COMPANION 전환은 명령과 일치하며
이전의 미확정 관찰을 앱 오인식 결함으로 판정하지 않는다.
연결 재생성 후 두 번째 시험에서는 NORMAL→음악 취향 질문→UNSUPPORTED를 관찰했다.
로컬 재생은 아직 확인하지 못했다. 이를 성공으로 바꾸거나 다른 음악으로 대체하지 않았다.

원인 추적을 위해 B LiveVoiceIntegration에 디버그 전용 메타데이터 hook를 추가했다.
기존 A Backend의 진단 이벤트/텍스트 길이, 반환 결과 종류, 명령 mode/검색어 유무,
LOCAL_ALIAS/OTHER_MUSIC/STOP 같은 고정 범주와 실행 결과만 logcat에 기록한다.
발화 원문·검색어·좌표·프로필·키는 기록하지 않는다. Release에서는 진단 출력을 하지 않는다.
기존 인식 결과와 미디어 실행 결과를 그대로 반환하며 판단/타이밍/음원 매핑은 변경하지 않는다.

## 재준비 후 실제 발화 관찰

음성 연결을 끄고 다시 켠 뒤 출발 확인을 실행했다.
상태 질문에서 TTS_DONE 이후 STT_REQUESTED → STT_READY → STT_FINAL → STT_RELEASED를 확인했고,
화면은 정상 응답이었다. 이어 모드 질문에서도 같은 순서의 최종 STT 콜백이 수신되었다.
모드 응답의 명령 분류는 UNKNOWN이었고 이후 추가 TTS_DONE을 관찰했다.
LOCAL_ALIAS 재생 요청은 없었다. 발화 원문은 수집·기록하지 않았다.
따라서 이번 시도는 최종 STT 수신까지 확인했으며, 로컬 재생과 재생 중 질문 전 중단은 미검증이다.
두 번째 발화 시점을 사용자에게 확인 중이며, 인식/명령 해석/입력 타이밍 중 원인은 아직 확정하지 않았다.

## 재질문 수정 후 자동 검증 및 실동작 준비

설계/질문 문맥 정책은 VOICE_REPROMPT.md에 먼저 작성했다.
A NormalDrivingCoordinator의 재질문에 ask(TTS 완료 후 STT)를 연결했다.
추가 재질문은 작업당 한 번, 음악 취향 질문도 한 번으로 제한하며 반복 모호함은 종료한다.
A ConditionQuestion에서 알려진 단일 질문의 최종 Text만 해석한다.
피로 질문의 아니요는 정상 보고, 예는 가벼운 피로 보고이며 문맥 없는 답은 미확인이다.
회복 질문의 복합 문장을 단일 피로 질문으로 통일하고 안 졸림 등의 명시적 부정을 추가했다.
기존 회복 확인 횟수/명시적 재개 동의/오류 종류는 유지했다.
공통 계약, B UI/차량/내비/외부 콘텐츠 구현과 SDK/Gradle 버전은 변경하지 않았다.
LiveVoiceIntegration의 앞선 디버그 진단 변경은 그대로 유지했다.

최종 수정 후 실제 실행:
- :app:testDebugUnitTest :app:assembleDebug :app:lintDebug — BUILD SUCCESSFUL, 44초.
- XML 집계: 445 tests, 0 failures, 0 errors.
- lint: 오류 0, 경고 25.
- Fake/가상 시간: 이전 모드 거절→재질문→로컬 명령, TTS 완료 전 청취 차단,
  위험 선점 정리, 반복 모호함 상한, 재질문 인식 오류의 미디어 실행 차단,
  문맥별 예/아니요, 명시적 부정, 상충 표현, 오류 결과 보존,
  A 세션 연결과 휴식 후 두 번 회복 확인/재개 거절을 검증했다.
- git diff --check: 공백 오류 없음(CRLF 변환 안내만 있음).

수정 APK를 emulator-5554에 install -r로 업데이트했다.
기존 내비 안내가 유지됨을 화면에서 관찰하고, DriveMate 내 실제 내비/차량 읽기를 다시 연결했다.
기존 시험 프로필과 8초 테스트 음을 선택하고 실제 음성 연결의 준비 완료를 확인했다.
이 단계에서는 출발 버튼을 누르거나 사용자 발화를 대신하지 않았다.
사용자 준비 응답 대기 중이므로 수정 후 전체 실제 발화/로컬 재생/재생 중 선점은 아직 미검증이다.
커밋·푸시는 하지 않았다.

## 수정 후 실제 발화 시험 — 로컬 재생 확인

사용자 준비 응답 후 UI의 출발 확인을 실행했다. 수정 APK의 실제 Pleos 경로(PID 6831)에서
상태 질문 최종 STT → NORMAL 화면 → 이전 모드 질문 최종 STT → 재질문 TTS 완료 →
STT_REQUESTED/READY → STT_FINAL/RELEASED → SELECT_MUSIC(hasQuery=true) →
STOP SUCCESS → LOCAL_ALIAS SUCCESS를 순서대로 확인했다.
발화 원문은 기록하지 않았고 중간 결과를 최종으로 사용하지 않았다.
재질문 TTS 완료에서 다음 STT 요청까지 약 28ms, 로컬 재생 성공은 마지막 최종 STT 후 약 97ms였다.
로컬 포트의 성공은 실제 player.state.playing 확인 후 반환하는 결과다.
이어 화면에서 정상 응답과 ‘재생을 마쳤습니다’를 확인했다.
이 시험에서는 재생 중 상태 다시 확인에 따른 STOP/TTS/STT 기록은 없었다.
따라서 재질문 청취와 실제 로컬 플레이어 시작/종료는 확인했으나, 사람이 들은 소리 여부는
사용자 확인 대기이며 재생 중 선점→질문→최종 답변의 전체 연결은 아직 미검증이다.

사용자는 수정 후 시험에서 테스트 음을 실제로 들었으며, 상태 확인 버튼은 누르지 않았고
소리가 계속 재생되다 끝났다고 확인했다. 따라서 이 시도는 사용자 청취까지 확인된 실제
로컬 재생 성공이며, 재생 중 선점 시험으로 간주하지 않는다. 다음 시험에서는 재생 상태를
관찰한 뒤 검증자가 상태 확인 버튼을 눌러 중단→TTS→STT를 확인할 예정이다.

## 재생 중 선점 추가 시도 — 미완료

다음 시도는 정상 상태 응답 뒤 음악 명령이 SELECT_MUSIC으로 분류됐으나
OTHER_MUSIC/UNSUPPORTED였다. LOCAL_ALIAS 성공이 없으므로 선점 시험은 실행하지 않았다.
짧은 별칭을 안내하고 다시 출발 확인한 시도에서는 첫 최종 STT가 UNKNOWN으로 분류됐고
화면의 상태도 미확인이었다. 원문 없이 기록하므로 인식된 문장의 구체적 차이는 확정할 수 없다.
해당 시도는 음악 선택/재생으로 진행되지 않았다. 사용자에게 첫 질문의 청취/답변 시점을 확인 중이다.
앞선 실제 로컬 재생 성공과 재질문 STT 성공은 유지되지만, 재생 중 선점→TTS→실제 최종 STT의
전체 수동 시험은 여전히 미완료다. Mock/중간 결과/임의 별칭 매핑으로 성공 처리하지 않았다.

## 추가 실제 시험 — 2026-11-12

사용자 요청으로 출발 세션을 UI에서 재생성하고 실제 발화 시험을 실행했다.
초기 준비 중 입력 충돌로 거부된 클릭은 재관찰 후 실행했으며, 입력 감지를 우회하지 않았다.
최종 실행에서 실제 Pleos TTS_DONE → STT_REQUESTED/READY → STT_FINAL/RELEASED를
상태 질문과 모드 질문 모두에서 확인했고 상태 화면은 NORMAL(정상 응답)이었다.
모드 결과는 SELECT_MUSIC(hasQuery=true)였으나 미디어 포트에서는 OTHER_MUSIC,
UNSUPPORTED로 반환됐고 STOP SUCCESS로 정리됐다. LOCAL_ALIAS 성공은 없었다.
발화 원문/검색어/좌표를 읽거나 저장하지 않았으므로 정확한 인식 문자열의 불일치 원인은
확정하지 않는다. UI에서도 재생 중 표시는 없었고 로컬 재생 중단 상태를 확인했다.
재생을 시작하지 못했으므로 상태 확인을 통한 재생 중 선점은 실행하지 않았다.
앞선 사용자 청취가 확인된 로컬 재생 성공은 유지하되, 이번 시도를 성공으로 표시하지 않는다.
남은 항목: 실제 인식과 로컬 별칭 매칭 불일치 원인 확인 후 재생 중 STOP 완료→TTS→최종 STT.
이번 추가 시험에서는 코드 변경/재빌드/단위 테스트 재실행/커밋/푸시는 하지 않았다.

## 별칭 불일치 원인 추적 준비

원인이 확인되기 전 재생 허용 규칙을 확대하지 않았다.
LocalMediaDiagnostic의 고정 분류(EXACT_ALIAS/NFC_ONLY/OBJECT_PARTICLE/LATIN_TEST/
MISSING_AUDIO_NOUN/OTHER)를 추가하고 debug 전용 기존 hook에 연결했다.
원문/검색어 대신 분류와 LOCAL/EXTERNAL 포트, 요청 직전 재생·준비 여부만 기록한다.
SDK 호출, 미디어 포트의 실제 판단/반환값과 B UI/차량/내비/외부 콘텐츠 기능은 변경하지 않았다.
진단은 최종 MediaCommand를 관찰할 뿐 부분 인식 결과를 실행하지 않는다.
자동 검증: testDebugUnitTest/assembleDebug/lintDebug BUILD SUCCESSFUL(53초),
447 tests / 0 failures / 0 errors, lint 오류 0 / 경고 25.
진단 APK를 emulator-5554에 설치하고 기존 안내/차량 읽기/선택 프로필/테스트 음/실제 음성을 준비했다.
새 진단을 이용한 실제 발화는 사용자 준비 응답 대기 상태다.
기존 기록의 OTHER_MUSIC만으로 조사 추가나 음성 누락을 확정하지 않는다.
재생 중 선점→질문→최종 STT 전체 검증은 미완료이며 커밋·푸시는 하지 않았다.

## 고정 별칭 진단 설치 후 첫 시도

사용자 준비 확인 후 실제 출발 확인을 눌렀다. 현재 진단 앱(PID 5622)에서 첫 TTS_DONE,
STT_REQUESTED/READY, STT_FINAL/RELEASED, Text 반환까지 수신했다.
이어 화면은 정상 대신 미확인이었고, 추가 안내 TTS_DONE 뒤 모드 청취 요청은 없었다.
PlayMusic 요청 자체가 없으므로 이번에는 별칭 진단 분류가 발생하지 않았다.
이 결과를 별칭 문제 재현이나 수정 성공으로 간주하지 않는다.
첫 상태 답변의 원문을 수집하지 않은 상태에서 사용자에게 실제 들은 질문 흐름을 확인했다.

## 정상 답변 미확인 진단 확장

사용자는 첫 질문에 정상 표현만 답했지만 모드 질문이 나오지 않았다고 확인했다.
음악 별칭보다 먼저 최종 Text의 상태 분류 경계를 확인하기로 했다.
기존 debug hook에 ConditionReport, 끝 문장부호를 제거한 경우의 ConditionReport,
물음표 존재 여부만 추가했다. 실제 분류/재생 로직은 바꾸지 않았다.
A PleosVoiceSmokeTest의 기존 ask 시나리오에도 같은 비식별 분류 메타데이터를 추가했다.
원문은 출력하지 않으며 matchesTestPhrase boolean과 길이/분류만 확인한다.
테스트는 단일 realSdkScenario + voiceScenario=ask만 선택 실행할 예정이며,
전체 계측/차량 setter 테스트는 실행하지 않는다.
추가 진단 debug 빌드/단위 테스트 BUILD SUCCESSFUL(17초), AndroidTest APK 빌드 성공(6초).
대상/테스트 APK install -r 성공. 단일 실제 발화 시험은 사용자 준비 응답 대기 중이다.
이번 추가 진단 뒤 lint는 재실행하지 않았으며 직전 진단 버전의 결과와 구분한다.

## 단일 실제 TTS→STT 상태 진단 성공

선택한 PleosVoiceSmokeTest의 voiceScenario=ask 하나만 실행했다. OK(1 test), 6.741초.
실제 STT/TTS 권한 granted=true. TTS_DONE → LISTENING → STT_REQUESTED/READY →
STT_FINAL(length=4) → STT_RELEASED를 수신했다.
최종 Text의 matchesTestPhrase=true, condition=NORMAL, afterTerminalPunctuation=NORMAL이었다.
원문은 출력하지 않았다. 서비스 close 후 UNAVAILABLE(폐기 상태)를 확인했다.
따라서 이 단일 시험에서는 실제 예상 발화 수신과 정상 분류가 모두 성공했다.
이 결과만으로 앞선 통합 미확인의 원인이나 로컬 별칭 문제 해결을 확정하지 않는다.
통합 앱의 기존 경로/차량 읽기/테스트 음/실제 음성을 다시 준비하고 사용자에게 다음 발화 준비를 요청했다.
재생 중 중단→TTS→최종 STT 통합 검증은 아직 미완료다.

## 통합 앱의 추가 진단 결과

사용자 준비 응답 후 출발 확인을 실행했다. 실제 앱(PID 9394)에서 첫 최종 STT는
condition=NORMAL, afterTerminalPunctuation=NORMAL, hasQuestionMark=false였다.
이후 모드 TTS와 최종 STT를 정상 수신했다. 모드 명령은 SELECT_MUSIC(hasQuery=true)였으나
request=MUSIC, alias=OTHER, port=LOCAL, wasPlaying=false, wasPreparing=false였고
OTHER_MUSIC/UNSUPPORTED 뒤 STOP SUCCESS로 정리됐다.
단순 조사 추가/영문 test/정의한 명사 누락/NFC 차이 범주에 해당하지 않았다.
OTHER만으로 실제 인식 문자열이나 원인을 확정하지 않으며 유사도를 근거로 재생하지 않는다.
정상 상태 전달 경로는 이번 실제 통합 시도에서 성공했다. 앞선 미확인의 원인까지 소급 확정하지 않는다.
기존 ‘원문 없이’ 제한을 유지한 채 고정 진단의 한계에 도달해, 다음 시험의 최종 문구만
채팅에서 일시 확인하는 범위 변경을 사용자에게 질의했다. 답변 전 원문 수집/출력은 하지 않았다.
재생 중 중단→TTS→최종 STT 통합 시험은 미완료다.

## 사용자 한시 허용에 따른 단일 별칭 확인

사용자가 이번 시험의 최종 문구만 채팅에서 확인하도록 명시적으로 허용했다.
차량/내비/미디어 호출 없는 단일 실제 TTS→STT 시험을 실행했고 OK(1 test), 8.069초였다.
최종 STT를 수신해 A 명령 해석 결과 EXACT_ALIAS를 확인했다. 원문은 문서/파일에 기록하지 않는다.
이번 시도에서는 정상 별칭이 전달됐으므로 앞선 OTHER의 원인을 확정할 수 없다.
조사/유사어 자동 매핑이나 임의 재생 허용은 추가하지 않았다.
한시적 최종 문구 반환 코드를 시험 직후 소스에서 제거했으며 테스트 APK도 재빌드/교체한다.
기존 비식별 메타데이터만 남긴다. 실제 전체 재생 중 선점 검증은 여전히 미완료다.

한시적 문구 반환 기능 제거 후 assembleDebugAndroidTest/testDebugUnitTest/lintDebug 재검증 성공
(38초, unit test task는 up-to-date). 제거된 테스트 APK install -r 성공으로 기기에서도 교체했다.
소스 검색에서 inspect-local/inspectedFinal/allowFinalPhrase 경로가 없음을 확인했다.
커밋·푸시하지 않았다.

## 2026-11-13 재인계 검토

사용자가 현재 작업을 여자친구(개발자 B)에게 넘기기 위해 커밋·푸시를 요청했다.
B_HANDOFF.md 맨 위에 최신 브랜치/변경 내용/인수 절차/미검증 항목을 정리했다.
커밋 전 assembleDebug/testDebugUnitTest/lintDebug/assembleDebugAndroidTest 실행 성공(9초).
82 tasks 중 81 up-to-date이며 단위 테스트는 재실행하지 않고 기존 447개 통과 XML을 재확인했다.
전체 계측 및 차량 setter 테스트는 실행하지 않았다. 실제 발화 시험도 추가하지 않았다.
feature/voice-state만 푸시하며 main/develop/B 브랜치에는 직접 변경하지 않는다.
작업과 무관한 기존 미추적 파일은 커밋에서 제외한다.
