# 실제 음성·로컬 미디어 통합 시험 재개 — 2026-11-14

기준: feature/voice-state / af99833. B_HANDOFF.md의 2026-11-13 절,
VOICE_REPROMPT.md, B5_VOICE_MEDIA.md, VOICE_MEDIA_VALIDATION_2026-11-12.md를 확인했다.

## 저장소 보존

- git fetch origin 후 현재 브랜치는 원격과 ahead/behind 0/0.
- 원격 B 브랜치 최신은 기존 e9d9679. 추가 변경을 병합하지 않았다.
- 기존 작업과 무관한 미추적 파일은 수정/삭제/커밋하지 않았다.
- 소스 변경, 커밋·푸시, 외부 콘텐츠·차량 제어 확장은 하지 않는다.
- 이전 한시적 원문 확인 허용은 종료됐으며 이번에는 비식별 진단만 사용한다.

## 자동 확인

- assembleDebug/testDebugUnitTest: BUILD SUCCESSFUL(13초), 43 tasks 모두 up-to-date.
- 단위 테스트는 새로 실행한 것이 아니며 기존 XML은 447개, 실패/오류 0.
- 전체 계측/차량 setter 테스트는 실행하지 않았다.

## 환경

- 처음에는 연결 장치가 없어 기존 Pleos_Connect_v2.1.0 AVD를
  -no-snapshot-load/-no-snapshot-save로 시작했다. 데이터 초기화는 하지 않았다.
- emulator-5554 부팅 완료(sys.boot_completed=1).
- CAAS 4.2.10-coral-aosp / versionCode 4002010.
- STT_SERVICE와 TTS_SERVICE granted=true.
- Android mic mute의 switch/restrictions/API/system 값 모두 false.
- Extended Controls에서 Enable Host Microphone Access가 이미 켜져 있음을 관찰했다.
  마이크/개인정보 설정을 변경하지 않았다.
- 실제 내비 화면에 활성 안내가 없음을 관찰했다. 사용자가 시험용 목적지를 정하고
  안내를 시작하도록 요청했다. 목적지/좌표/발화 원문은 기록하지 않는다.
- 아직 출발 확인이나 마이크 청취를 시작하지 않았고, 이번 전체 통합 시험은 미실행이다.

## 남은 실행

실제 경로·프로필·테스트 음·실제 음성 연결 준비 → 사용자 준비 확인 →
최종 STT/NORMAL → 로컬 음악 명령 → 실제 플레이어 재생 →
재생 도중 상태 확인 → wasPlaying=true인 STOP 요청의 성공 → TTS_DONE →
STT_REQUESTED/최종 STT → 상태 갱신과 자동 음악 재개 없음 확인.

과거의 개별 성공을 이번 전체 통합 성공으로 표시하지 않는다.

## 사용자 안내 시작 후 앱 준비

사용자가 시험 경로 안내를 시작했다고 확인했고 실제 내비의 활성 안내 화면을 관찰했다.
기존 목적지를 변경하지 않고 DriveMate로 복귀했다. 기존 시험 프로필이 선택돼 있음을 확인하고
실제 내비 읽기/차량 상태 읽기만 연결했다(P단, 속도 0, 안전 주차는 미확인 유지).
8초 테스트 음을 선택하고 실제 음성 연결을 켰다.
준비 완료 안내, 음성 IDLE, 실제 음성 켜짐, 활성 출발 확인 버튼을 확인했다.
아직 출발 버튼/청취를 실행하지 않고 사용자 발화 준비를 요청했다.

## 첫 시도와 다른 소리 중지 후 시도

첫 시도는 최종 STT(length=10)를 수신했으나 condition/afterTerminalPunctuation 모두
UNCONFIRMED였다. 모드 선택/재생은 실행되지 않았다. 원문 없이 진단했다.
사용자는 다른 소리를 멈췄다고 확인하고 재시험에 응했다. 다른 소리가 앞선 모든 실패의
원인이라는 결론은 내리지 않는다.

재시험에서 실제 최종 STT → condition=NORMAL → 모드 질문 → 최종 STT →
SELECT_MUSIC(hasQuery=true), EXACT_ALIAS/LOCAL → LOCAL_ALIAS SUCCESS를 확인했다.
화면에서도 ‘선택한 로컬 오디오 재생 중’을 관찰하고 상태 확인 버튼을 눌렀다.
그러나 로그상 Play 성공 후 STOP 요청까지 약 12.418초였으며 STOP의 wasPlaying=false였다.
음원은 8초이므로 이 시도는 재생 중 선점의 근거가 아니다. 화면 캡처 지연을 고려해야 한다.
STOP SUCCESS → TTS_DONE → STT_REQUESTED/READY → 최종 STT/RELEASED → NORMAL은 확인됐다.
따라서 상태→음악 재생→후속 실제 질문/최종 답변 연결은 확인했지만 재생 중 중단은 미검증이다.
다음 시도에서는 사용자가 소리를 듣는 즉시 상태 확인을 누르도록 요청했다.
기기 시계는 호스트와 다르므로 기록 날짜는 사용자 기준 2026-11-14로 유지하고 순서/시간차만 비교한다.

## 최종 수동 통합 시험 — 재생 중 선점 성공

사용자 준비 확인 후 음성 연결을 껐다 켜 출발 세션을 재생성했다.
사용자가 실제 음성을 제공하고, 로컬 소리가 들리면 즉시 상태 확인을 직접 누르는 방식으로 실행했다.
실제 앱의 비식별 진단에서 다음 순서를 확인했다(원문/좌표/키는 수집하지 않았다).

1. TTS_DONE → STT_REQUESTED/READY → STT_FINAL(length=4)/RELEASED → condition=NORMAL.
2. 모드 TTS_DONE → STT_REQUESTED/READY → STT_FINAL(length=9)/RELEASED → SELECT_MUSIC(hasQuery=true).
3. request=MUSIC, alias=EXACT_ALIAS, port=LOCAL → LOCAL_ALIAS SUCCESS.
4. Play 성공 약 1.391초 후 STOP 요청: **wasPlaying=true**, wasPreparing=false.
5. 약 33ms 뒤 STOP SUCCESS(실제 플레이어 중단/해제 조건 확인 후 반환).
6. 이후 TTS_DONE → STT_REQUESTED(완료 후 약 8ms)/READY → STT_FINAL(length=4)/RELEASED → condition=NORMAL.
7. 최종 화면에서 음성 IDLE, 정상 응답, 로컬 재생 중단을 관찰했다.
   이후 관찰 기간에 새 Play 요청이나 자동 음악 재개는 없었다. 모든 미래 상황의 보장은 아니다.

이번 시험은 실제 Pleos STT/TTS, 실제 로컬 플레이어와 사용자 발화를 사용했다.
Fake/부분 STT를 최종 답변으로 대체하지 않았다. 기존 내비는 읽기만 사용했고 차량 setter는 실행하지 않았다.
이번 요청의 핵심인 실제 발화→NORMAL→로컬 재생→재생 중 중단→TTS→최종 STT/NORMAL 흐름은
한 시도에서 확인됐다. 단, 앞선 미확인/별칭 불일치의 모든 원인이 해결됐다는 의미는 아니다.
사용자가 다른 소리를 멈춘 뒤 성공했으나 그것만으로 인과관계를 확정하지 않는다.

## 최종 범위

소스/외부 콘텐츠/차량 제어 기능 변경 없음. 이번 로컬 검증 문서만 추가했다.
기존 미추적 파일 보존, feature/voice-state 유지, 커밋·푸시하지 않음.
빌드·단위 테스트는 앞서 기록한 캐시 재사용 검증이며, 이번 실제 수동 시험과 구분한다.
외부 음악/뉴스, 안전 휴식 장소 검색, 차량 변경·복원, 실차 검증과 장시간 안정성은 이번 범위가 아니다.

## 중복 질문 수정 APK 전체 흐름 재시험

DriveMateSession 수동 확인의 도래 질문 병합 수정 후 새 실제 음성 세션의 출발 확인으로 시작했다.
첫 최종 STT(length=4)는 NORMAL, 모드 질문의 최종 STT(length=9)는 SELECT_MUSIC/EXACT_ALIAS,
LOCAL_ALIAS SUCCESS였다. 중간 모드 응답의 condition=UNCONFIRMED는 공통 진단 분류이며
음악 명령의 상태 오적용을 의미하지 않는다.
로컬 재생 성공 약 3.95초 뒤 STOP 요청 시 wasPlaying=true였고 44ms 뒤 SUCCESS였다.
이후 TTS_DONE→8ms 뒤 STT_REQUESTED→READY→FINAL(length=4)/RELEASED를 확인했다.
그러나 마지막 상태 최종 응답은 condition=UNCONFIRMED였다. 전체 흐름을 모두 성공으로 표시하지 않는다.
원문을 확인하지 않아 STT 오인식/사용자 실제 표현/분류 사전 원인을 확정하지 않는다.
관찰 성공: 정상→모드→로컬 재생→활성 재생 중단 완료→TTS 완료→최종 STT 수신.
남은 확인: 마지막 상태 응답 분류의 간헐 실패. 실제 소리·발화 확인은 사용자에게 요청한다.
코드/SDK/외부 콘텐츠 변경 없이 기존 수정 APK를 시험했다. 커밋·푸시하지 않았다.

사용자는 테스트 음이 재생 중 중단됐으며 마지막에도 지정된 정상 표현만 발화했다고 확인했다.
음악 중단 청감과 wasPlaying=true→STOP SUCCESS 관찰은 일치한다.
첫 상태 최종 결과와 마지막 상태 최종 결과는 길이가 같았지만 분류는 NORMAL/UNCONFIRMED로 달랐다.
동일한 ConditionClassifier는 동일 문자열에 항상 같은 결과를 반환하며 상태별로 사전을 바꾸지 않는다.
따라서 길이만으로 같은 STT 문자열이었다고 판단할 수 없다. 오인식/구성 문자 차이의 가능성은 있으나
원문을 보지 않았으므로 어느 것이 원인인지 확정하지 않는다. 정상 판정 규칙을 임의로 완화하지 않았다.
후속 진단은 최종 결과의 지정 시험 문구 일치 여부와 정규화 범주 등 고정 메타데이터로 한정해야 한다.

## 최종 STT 시험 문구 비식별 진단 준비

A PleosVoiceBackend의 debug 최종 콜백에만 고정 이벤트 STT_FINAL_TEST_<범주>를 추가했다.
EXACT / NFC_WHITESPACE_PUNCTUATION / UNICODE_SPACE / FORMAT_CHARACTER /
COMPATIBILITY / DIFFERENT만 전달한다. 원문·문자 코드·해시·음성·좌표를 출력/저장하지 않는다.
지정 문구는 정상 시험 표현 하나로 고정하며 음악 응답의 DIFFERENT는 정상적인 결과다.
진단은 반환 응답/ConditionClassifier를 변경하지 않는다. 물음표·쉼표·다른 의미·상충 표현은
임의로 정상 처리하지 않는다. 실제 결과가 DIFFERENT이면 오인식과 표현 차이를 추가 구분할 수 없다.
SDK 시그니처/의존성/공통 계약/B 코드 변경 없음. 기존 수동 질문 중복 수정도 보존한다.
다음 실제 시험은 첫 상태·모드·재생 중단 후 상태의 세 최종 콜백을 순서대로 비교한다.

비식별 진단 추가 후 assembleDebug/testDebugUnitTest 전체 실행 BUILD SUCCESSFUL(20초),
43개 작업 중 9개 실행/34개 up-to-date. XML 453개, 실패/오류 0.
설치 adb install -r Success. 실제 발화 시험은 사용자 준비 후 별도로 진행한다.

### 비식별 진단 첫 실제 시도

출발 상태 질문의 FINAL(length=3)→STT_FINAL_TEST_DIFFERENT→UNCONFIRMED를 확인했다.
시험 문구와 일치하지 않았고 NFC/일반 공백/허용 끝 문장부호/Unicode 공간/format/NFKC
정규화 범주에서도 일치하지 않았다. 모드 질문과 음악 단계로 진행되지 않았다.
이는 최종 STT 수신 자체의 실패가 아니라 시험 문구와 다른 결과 수신이다.
정확한 오인식 문구/음향 원인은 원문을 확인하지 않아 알 수 없다. 정상 판정 완화 없음.

### 전체 흐름 재시험에서 확인한 원인과 최소 보완

사용자는 첫 시도에도 지정 문구만 발화했다고 확인했고 독립 세션으로 재시험했다.
첫 상태 FINAL(length=4)는 TEST_EXACT/NORMAL, 음악 최종은 TEST_DIFFERENT/SELECT_MUSIC,
LOCAL_ALIAS SUCCESS였다. 약 2.151초 뒤 wasPlaying=true STOP, 21ms 뒤 SUCCESS,
TTS_DONE→9ms 뒤 STT_REQUESTED→최종 수신을 확인했다.
마지막 FINAL(length=5)는 TEST_DIFFERENT, hasQuestionMark=true,
condition=UNCONFIRMED/afterTerminalPunctuation=NORMAL이었다. 원문은 출력/저장하지 않았다.
따라서 이 시도에서는 질문 문장부호로 인한 보수적 분류가 미확인의 직접 원인이다.
이는 앞선 길이 4/물음표 없음 등 다른 실패의 원인까지 설명하지 않는다.

보완 정책: 끝 물음표가 있는 미확인 응답이 물음표만 제외했을 때 정상 사전과 일치하면
DriveMateSession이 같은 피로 질문을 최대 한 번 재확인한다. 원래 답변을 NORMAL로 강제 변환하지 않는다.
재확인 결과만 기존 문맥 해석·상태 머신에 전달하며 반복 모호 응답/실패는 그대로 구분한다.
위험 표현/임의 다른 단어까지 추측하지 않는다. B 코드·공통 계약·SDK 메서드 변경 없음.

재확인 보완 후 전체 단위 테스트 455개 실패/오류 0, assembleDebug/testDebugUnitTest
BUILD SUCCESSFUL(26초). 정상 재확인/반복 모호 응답의 최대 1회 경계를 회귀 테스트했다.
최신 보완 APK의 실제 전체 발화 재검증은 아직 남아 있다. 모든 간헐 오류 해결로 표시하지 않는다.

### 재확인 보완 APK 실제 시험

첫 상태 FINAL(length=5)/물음표 없음은 NORMAL, 모드 최종은 SELECT_MUSIC/LOCAL_ALIAS SUCCESS.
재생 약 2.494초 후 wasPlaying=true STOP 요청, 33ms 뒤 SUCCESS,
TTS_DONE→9ms 뒤 STT_REQUESTED→최종 수신을 확인했다.
마지막 FINAL(length=3)은 TEST_DIFFERENT, 물음표 없음, MILD_FATIGUE로 분류됐다.
따라서 물음표 정상 표현 재확인 분기는 이번 실제 시험에서 실행되지 않았다.
새 분기의 실제 실행 성공으로 보고하지 않는다. 마지막 실제 발화는 사용자에게 확인 요청한다.
활성 음악 중단과 TTS/STT 순서는 확인했으나 정상 복귀 전체 성공이 아니다.

사용자는 마지막 응답에서 부정 졸음 표현을 발화했으며 직접 음성 연결을 끄지는 않았다고 확인했다.
실제 최종 STT 메타데이터는 length=3/MILD_FATIGUE였다. 사용자 의도와 수신 결과의 불일치 사례로 기록한다.
부정어 누락 가능성은 있지만 원문 미확인으로 정확한 결과/누락 위치/음향 원인을 확정하지 않는다.
원래 부정 졸음 표현은 기존 ConditionClassifier의 NORMAL 사전에 포함되어 있다.
따라서 이 사례를 분류 사전의 부정 표현 미지원이나 상태별 사전 변경으로 설명하지 않는다.
수신된 피로 표현을 임의로 정상으로 바꾸는 보정은 하지 않는다. 물음표 재확인 실제 실행은 여전히 미검증이다.

## 후속 STT 준비 시점 단독 시험 계획

앱 음성 연결 꺼짐을 확인한 상태에서 기존 PleosVoiceSmokeTest의 listen 시나리오 하나만 선택한다.
40초 청취를 준비하고 SDK STT_READY가 실제 관찰된 뒤 채팅으로 발화 시점을 안내한다.
앱 LISTENING 표시가 READY보다 앞선다는 구현 사실과 실제 입력 실패의 인과관계는 구분한다.
시험 문구는 기존 고정 진단이 지원하는 정상 표현을 사용한다. 원문·오디오 저장 없음.
새 SDK 메서드/B UI/미디어/차량/내비 변경 없음. 전체 계측/차량 setter 시험은 실행하지 않는다.
assembleDebugAndroidTest BUILD SUCCESSFUL(4초), 50개 작업 중 1개 실행/49개 up-to-date.
이는 계측 APK 빌드이며 실제 발화 시험 성공이 아니다.

## STT_READY 이후 지연 발화 단독 실제 결과

기존 PleosVoiceSmokeTest realSdkScenario/voiceScenario=listen 하나만 실행했다.
두 Pleos 권한 granted=true. 단조 시각 LISTENING=3735983, STT_REQUESTED=3735998,
STT_READY=3736478, 첫 STT_STARTED=3750929, FINAL=3751489, RELEASED=3751497이었다.
LISTENING은 READY보다 495ms 먼저 표시되었지만, 이번 발화 시작 감지는 READY 약 14.451초 후였다.
최종 메타데이터 length=5, TEST_DIFFERENT, matchesTestPhrase=true,
condition=UNCONFIRMED/afterTerminalPunctuation=NORMAL을 관찰했다.
기존 matchesTestPhrase 검사는 공백 및 끝 .!?를 제외한 일치 여부이며 원문 출력은 없다.
이번에는 준비 완료 후 발화해도 끝 물음표 관련 미확인이 발생했다.
따라서 준비 전 발화만으로 이 미확인을 설명할 수 없다. 과거 부정어 누락 가능성까지 부정하는 결과는 아니다.
계측 OK(1 test), 15.602초. 이는 SDK 최종 수신/해제 성공이지 condition=NORMAL 성공이 아니다.
STT_RELEASED와 closed=UNAVAILABLE 확인. 자동 테스트와 실제 수동 발화를 구분한다.
물음표 정상 응답의 1회 재확인 보완은 자동 검사 통과 상태이며 실제 전체 앱 분기 실행 확인은 아직 남았다.

## 최대 1회 재확인 실제 앱 실행 확인

새 실제 앱 세션에서 상태 다시 확인만 수동 실행했다. 음악/모드 선택은 이 시도에 포함하지 않았다.
첫 FINAL(length=5)/RELEASED는 UNCONFIRMED, hasQuestionMark=true,
afterTerminalPunctuation=NORMAL이었다. 이후 재확인 TTS_DONE→STT_REQUESTED/READY,
두 번째 FINAL(length=5)/RELEASED는 NORMAL, hasQuestionMark=false였다.
재확인 TTS 완료 뒤 약 25ms 후 STT_REQUESTED가 관찰됐다.
따라서 실제 앱에서 물음표 응답→1회 재확인→다음 최종 응답 정상 분류 흐름을 확인했다.
첫 응답을 정상으로 강제 변경하거나 부분 결과를 사용하지 않았다.
반복 모호 응답의 종료 경계는 자동 테스트로 확인한 것이며 실제로 반복 모호 응답을 주입하지 않았다.

최신 변경은 A ConditionQuestion/DriveMateSession/PleosVoiceBackend와 A 진단 유틸·테스트 및 이 문서다.
앞선 수동 질문 중복 수정과 미추적 파일을 보존했다. B 코드·SDK 버전·차량/내비/외부 콘텐츠 변경 없음.
최신 자동 검증은 빌드 및 전체 단위 테스트 455개 성공이고 실제 관찰은 위 시도별로 구분한다.
남은 한계: 다른 최종 문구 수신/부정어 누락 가능성은 미해결이며 물음표 사례와 별개다.
최신 재확인 보완을 포함한 음악 전체 흐름의 최종 NORMAL까지는 단일 시도에서 아직 확인하지 못했다.
커밋·푸시하지 않았다.
