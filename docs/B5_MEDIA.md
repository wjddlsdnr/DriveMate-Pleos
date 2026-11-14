# B5-1 설치된 미디어 서비스 연결

2026-11-12. B5 전체 완료가 아닌 실제 서비스 연결 및 사용자 선택 로컬 오디오 재생의 첫 단계다. 기존 Android 프로젝트, Gradle/SDK 버전, A 코드와 공통 contract를 유지한다.

## 사용자 선택 로컬 오디오

사용자가 외부 서비스보다 로컬 파일 실제 재생을 먼저 선택했다. `LocalAudioPlayer`와 화면의 ‘로컬 오디오 · 실제 재생’을 추가했다. OpenDocument의 audio/*로 선택한 content URI만 메모리에 보관하며, 선택 결과에서는 자동 재생하지 않는다. 새 ‘선택한 파일 재생’ 조작과 최신 P단/정지 신호가 필요하다. 다음 곡/플레이리스트/백그라운드 서비스는 이번 범위에 없다.

MediaPlayer의 prepareAsync 완료와 실제 isPlaying을 확인한다. 로컬 읽기 권한 거부, 디코딩 실패, 준비 시간 초과를 실패로 표시한다. USAGE_MEDIA/CONTENT_TYPE_MUSIC AudioFocusRequest가 승인된 뒤만 재생하며, 영구/일시/duck 포커스 손실 모두 중단·해제한다. focus gain에 자동 재개하지 않는다. MediaSession은 현재 재생의 pause/stop만 노출하며 로컬 파일 이름/URI는 시스템 메타데이터에 내보내지 않는다.

백그라운드 이동 시 재생과 준비 작업을 중단하고 플레이어/세션/포커스를 해제한다. 프로필/가상 시나리오 변경은 파일 선택도 버린다. URI/파일명/오디오 내용은 영속 저장·로그·프로필에 남기지 않고 persistable URI 권한도 취득하지 않는다. FileProvider는 generated test tone의 좁은 cache 하위 폴더에만 접근하는 debug 소스셋 전용이며 release에는 포함되지 않는다. 테스트는 사용자 파일을 읽거나 복사하지 않는다.

이는 개발용 foreground 파일 플레이어다. Pleos 배포용 완전한 Music/Audio 앱의 MediaBrowserService/MediaLibraryService 구현, 실제 A 음성 세션의 안전 선점과 전체 CarUxRestrictions 대응을 완료한 것이 아니다. STT/TTS와 연결하기 전 해당 오디오 소유권 통합이 필요하다. 현재 가상 음성/시나리오에 실파일 재생을 연결하지 않는다.

공식 근거: https://developer.android.com/training/data-storage/shared/documents-files , https://developer.android.com/media/platform/mediaplayer/basics , https://developer.android.com/media/optimize/audio-focus

## 구현과 경계

- `data/media/AndroidMediaServiceGateway.kt`: Android 표준 MediaBrowser로 내보낸 서비스를 검색·연결하고 목록을 최대 30개 표시한다. MediaController.TransportControls의 playFromMediaId/playFromSearch/skipToNext/stop을 지원 동작 비트 확인 후 호출한다. 별도 Pleos 미디어 SDK나 비공개 호출을 만들지 않았다.
- Manifest에 공식 MediaBrowserService intent용 queries만 추가했다. 모든 앱 조회, 알림 접근, MEDIA_CONTENT_CONTROL, 외부 서비스 키, 인터넷 권한을 추가하지 않았다. Gradle 의존성 변경 없음.
- `ServiceMediaController`: 기존 A MediaController 포트를 구현한다. 연결되지 않은 호출, 빈/과도한 검색어, 폴더 재생, 뉴스 미연결을 실패로 반환한다. 응답 기한 및 연결 세대 검사를 적용한다.
- 재생 호출은 void이므로 호출 반환만으로 성공 처리하지 않는다. 후속 서비스 콜백에서 PLAYING과 비어 있지 않은 곡 ID/제목을 관측한다. ID 지정 재생은 요청 ID와 일치해야 한다. 검색·다음은 이미 재생 중인 같은 곡의 중복 알림으로 성공하지 않는다. 검색 결과의 질/검색어 일치·실제 소리 출력까지 증명하지는 않는다. 곡 정보가 없는 서비스는 보수적으로 시간 초과 처리할 수 있다.
- 중단·다음은 현재 연결에서 확인한 재생 곡과 일치하는 경우에만 요청한다. 외부 변경으로 ID가 바뀌면 소유권을 버린다. 표준 미디어 콜백은 요청 ID/조작 주체가 없으므로 동시 수동 조작과 자체 요청을 완전히 구분하지 못한다.
- 타임아웃·취소는 외부 앱이 요청을 적용하지 않았다는 보증이 아니다. 자동 재시도/자동 복원/임의 다른 앱 중단을 하지 않고 선택한 앱에서 상태를 확인하도록 표시한다.
- `LiveMediaIntegration`과 `ui/media/MediaPanel`은 직접 사용자 조작에 의해서만 연결/재생한다. 연결 자체에는 재생 요청이 없다. 프로필/시나리오 전환·백그라운드·종료는 작업을 취소하고 콜백/브라우저를 해제한다. 외부 앱의 재생은 자동 중단하지 않는다. 복귀 시 자동 연결·자동 재생은 없다.
- 설정/목록/재생 UI는 차량 조회의 최신 P단·유한한 속도 절댓값 0.05m/s 이하를 요구한다. 키보드는 새로 추가하지 않고 저장된 첫 가수/장르를 검색어로 사용한다. 이는 개발용 UI 제한이며 안전한 주차 장소 확인 또는 완전한 CarUxRestrictions 연동이 아니다. 중단은 foreground에서 사용할 수 있다.

## A 통합과 미구현

DriveMateSession에는 기존 FakeMediaController를 유지했다. 현재 음성이 미리 정한 가상 응답이므로 실제 포트를 주입하면 가상 답변이 실서비스를 재생하는 문제가 생긴다. 일반 주행 상태 판단이나 질문을 B에서 중복 구현하지 않았다. 새 화면은 직접 검증용으로 분리해 표시한다.

실제 A 음성 세션과의 미디어 연결, 안전 질문 전 콘텐츠 중단/오디오 포커스 선점, CarUxRestrictions 전체 대응, 서비스 로그인/계정별 지원 검증은 후속이다. 외부 서비스 연결 경로에서는 외부 플레이어가 실제 재생과 포커스를 소유한다. 로컬 경로는 위 LocalAudioPlayer가 재생·포커스를 소유한다. 배포용 MediaBrowserService를 만든 것은 아니다.

뉴스 전용 콘텐츠 제공자는 미선정·미연결이며 PlayNewsAudio는 UNSUPPORTED다. 임의 뉴스 URL, 일반 음악 검색, 합성 답변을 실제 뉴스 서비스로 대신하지 않는다. 음악/뉴스 스트리밍 업체의 공식 제3자 클라이언트 허용 범위 및 인증은 제공자별 확인이 필요하다. 외부 생성형 AI는 추가하지 않았다.

## 직접 확인 순서

1. 프로필에 선호 가수/장르를 저장한다. 차량 조회를 켜고 속도 권한과 최신 P단·정지 조회를 확인한다.
2. ‘실제 음악 앱 연결 · 개발 검증’의 ‘설치된 음악 앱 조회’를 누르고 앱을 선택한다. 로그인/연결 거부 또는 비어 있는 목록을 성공 재생으로 해석하지 않는다.
3. 서비스 목록의 재생 가능한 항목 또는 프로필 검색어 재생 버튼을 직접 누른다. 이때 외부 앱에서 실제 재생될 수 있다.
4. 서비스 상태 확인과 실제 소리 출력을 구별한다. 중단/다음은 현재 연결에서 시작하고 확인한 곡에 한해 요청한다. 실패/취소/시간 초과 시 외부 앱의 상태를 직접 확인한다.
5. 연결 해제/앱 전환 후에는 외부 앱이 계속 재생할 수 있다. 필요하면 해당 앱에서 중단한다.

## 공식 근거

- https://developer.android.com/training/package-visibility/use-cases : 미디어 브라우저 클라이언트의 queries 선언
- https://developer.android.com/reference/android/media/browse/MediaBrowser : 서비스 연결·목록·해제
- https://developer.android.com/reference/android/media/session/MediaController.TransportControls : 표준 검색/ID 재생 요청
- https://developer.android.com/reference/android/media/session/PlaybackState : 지원 동작·재생 상태
- https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide : 미디어 앱 표준 통합과 자동 재생 정책. 이 문서는 음악 제공 앱의 정책이며 다른 업체 서비스 접근 권한을 보장하지 않는다.

## 검증

- 최종 제품 코드의 assembleDebug/testDebugUnitTest/lintDebug/assembleDebugAndroidTest: BUILD SUCCESSFUL(33초). 단위 422개, 실패/오류 0. lint 오류 0/경고 25. 이후 계측 테스트 수정 빌드 성공(4초).
- 최신 앱/계측 APK 설치 후 LocalAudioPlayerTest + MediaPanelTest + 기존 DriveMateScreenTest: 7개 통과(17.104초). 생성한 작은 음량의 3초 WAV로 실제 디코더 위치 100ms 이상 진행, 백그라운드 해제, 실제 별도 오디오 포커스 요청에 따른 중단, focus gain/foreground 복귀 시 자동 재개 없음 확인. 테스트 파일은 종료 시 삭제한다. 사람의 청취 확인이나 사용자의 파일 선택 결과까지 검증한 것은 아니다.
- 초기에 로컬 계측 함수 반환형 오류로 실행 초기화 실패했고 Unit 명시로 수정했다. 이후 고정 300ms의 위치 검사 실패를 관측했다. 재생 자체의 성공을 임의로 가정하지 않고 최대 2.5초 내 실제 위치가 증가하는지 기다리는 검사로 바꿔 통과했다. 중단/포커스 검사는 별도로 실제 수행했다.
- 앞선 실제 MediaDiscoveryTest 1개 진단 통과: 서비스 2개 검색, 블루투스 서비스 연결 성공/목록 조회 오류, 로컬 음악 서비스 연결 거부. 재생 요청 0회. ‘진단 테스트 통과’와 ‘서비스 재생 성공’을 구별한다.
- OpenDocument 후보는 자동차 DocumentsUIStubWithResult와 DocumentsUI PickActivity 두 개다. OS가 선택기를 제한할 수 있으며 이를 우회해 특정 Activity를 강제 실행하지 않는다. 사용자가 선택기로 실제 파일을 고르는 전체 흐름과 소리 청취는 수동 확인 항목이다.
- A 보호 경로 및 공통 contract 변경 없음. 현재 브랜치는 feature/b-integration이며 커밋/푸시/병합은 하지 않았다. 전체 기존 계측/실차 검증을 다시 수행했다고 주장하지 않는다.

실제 음성 선점/전체 차량 UI 제한, 검색 정확도, 뉴스 재생은 아직 확인하지 않았다. B4의 주차 브레이크 신호 미제공 및 실제 차량 변경·복원 미검증도 그대로 남아 있다.


## 파일 선택 미지원 기기 대응

사용자가 Pleos 파일 선택기에서 ‘파일 관리가 이 기기에서 지원되지 않음’을 확인했다. 시스템 제한을 해제하거나 DocumentsUI를 강제 실행하지 않았다. debug 빌드에만 ‘테스트 음 선택 · 8초’를 추가했다. 앱이 직접 생성한 작은 음량의 WAV를 기존 좁은 cache FileProvider로 선택하며, 기존 ‘선택한 파일 재생’을 따로 눌러야 한다. 최신 P단/정지 조건, 포커스 요청, 백그라운드 중단 정책은 동일하다. 실제 음악 파일 가져오기나 뉴스 연동 완료를 뜻하지 않는다.

DebugAudioSample은 debug/release 소스셋으로 분리했다. release는 available=false이며 샘플 생성·버튼·debug provider를 제공하지 않는다. Gradle/SDK 선언과 A 코드는 유지했다. assembleDebug/testDebugUnitTest/lintDebug/assembleDebugAndroidTest/compileReleaseKotlin 실행은 BUILD SUCCESSFUL(46초)이다. 실제 소리 청취는 사용자가 별도로 확인해야 한다.
동일 DebugAudioSample을 사용한 최신 LocalAudioPlayerTest 1개 통과(4.293초): 실제 위치 진행/백그라운드 해제/포커스 손실 중단/자동 재개 없음. 최신 APK를 설치했다.
