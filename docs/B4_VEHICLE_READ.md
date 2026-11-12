# B4-1 실제 차량 상태 조회

후속: [B4-2 직접 동의하는 공조 전원/창문 위치값 변경](B4_VEHICLE_CONTROL.md)을 별도 카드로 추가했다. 아래 ‘제어 없음’은 이 조회 카드의 범위다. 실제 제어 성공과 온도 조회 오류 해결을 뜻하지 않는다.

최종 검증: assembleDebug/testDebugUnitTest/lintDebug/assembleDebugAndroidTest BUILD SUCCESSFUL(34초). 단위 테스트 399개, 실패/오류 0. lint 오류 0/경고 25. 신규 및 기존 회귀 계측 15개 통과(55.965초). 실제 Vehicle 2.3.22/code74에서 기어·주차 브레이크·브레이크 페달, 공조 전원 1영역, 창문 위치 4영역 값을 수신했다. 공조 설정 온도는 capability 2영역을 확인했지만 각 get에서 SERVICE_UNAVAILABLE이어서 값 수신 성공은 아니다. 속도 역시 권한 미허용으로 PERMISSION_DENIED였으며 실제 속도값 수신은 미검증이다. 앱 초기 화면의 새 조회 카드 배치도 캡처로 확인했다. 기존 사용자 프로필은 유지하고 조회 비활성 상태로 앱을 열어두었다.

이번 범위는 **사용자가 별도로 켜는 실제 차량 조회**다. 공조·창문·좌석 변경, 원본 설정 복원, 수동 변경 출처 판별은 구현하지 않는다. 모든 실제 연동은 getter/capability만 사용하고 제어 권한이나 setter를 추가하지 않았다. A 코드·공통 contract와 기존 B1~B3 기능은 유지한다.

## SDK와 권한 대조

- 공식 [Vehicle SDK 추가](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup) 안내에 따라 Vehicle 2.3.2 의존성만 추가했다. 기존 Gradle/AGP/Kotlin/compile/target/min SDK 버전은 바꾸지 않았다.
- 설치 AAR의 실제 시그니처와 권한 어노테이션을 javap로 대조했다. 한국어 문서와 일부 영어 릴리즈 페이지의 버전 표기가 다르므로 설치 바이너리와 컴파일 결과를 기준으로 한다.
- 실제 에뮬레이터 Vehicle 서비스: 2.3.22/code74. 공식 소개는 2.3.21 기준이므로 동일 버전이라고 기록하지 않는다.
- Vehicle(context).initialize/getGear/getBrake/getHVAC/getWindow/release 사용. initialize 반환만으로 연결 성공을 표시하지 않고 실제 콜백 수신 여부를 본다.
- Gear.getCurrentGearSelectionState의 실제 @RequiresPermission은 CAR_DRIVE다. Brake의 capability/get도 CAR_DRIVE, HVAC는 CAR_CLIMATE, Window는 CAR_WINDOWS만 선언한다. [공식 권한 안내](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission)의 대표 목록보다 개별 설치 메서드를 우선 대조했다.
- HVAC 설정 온도는 CapabilityConfig/AreaBounds/HvacTemperatureConfigArray를 받는 최신 오버로드로 영역과 READ/READ_WRITE를 확인한 뒤 CELSIUS를 명시해 조회한다. Window도 영역별 읽기 접근과 원시 값 범위를 확인한다. write-only/none/빈 영역은 조회 불가로 표시한다.
- 온도는 설정 온도이며 실내 현재 온도가 아니다. 창문은 원시 범위를 그대로 보여주고 백분율·열림 방향으로 추정하지 않는다.
- 공조 전원은 별도 capability의 READ/READ_WRITE 영역을 확인한 뒤 getHvacPowerState로 읽는다. 온도 실패가 전원/창문/기어 조회 성공을 가리지 않는다.
- 속도·주차 브레이크는 SDK 페달 정보와 별개다. 설치 android-37.0/optional/android.car.jar의 실제 타입과 [AAOS VehiclePropertyIds](https://developer.android.com/reference/android/car/VehiclePropertyIds)를 대조해 CarPropertyManager의 PERF_VEHICLE_SPEED(Float,m/s), PARKING_BRAKE_ON(Boolean), global area 0을 조회한다. 상태 AVAILABLE일 때만 값을 사용한다.
- android.car 선택 라이브러리와 조회 권한 CAR_SPEED/CAR_POWERTRAIN을 선언했다. 속도 권한은 사용자가 조회 버튼을 눌렀을 때 요청한다. 거부해도 다른 조회는 별도로 시도한다. 조향/제동/좌석/CONTROL_CAR_* 권한은 없다.

SDK 검사 파일은 무시되는 app/build/vehicle-inspection에만 둔다. 바이너리를 커밋하지 않는다. [공식 비동기 수명주기 안내](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/obtain-domain-object)에 따라 콜백을 기다린 뒤 해제하며, 취소/시간 초과 후 늦은 콜백은 취소된 deferred를 통해 UI에 반영하지 않는다.

## 구현과 수명주기

- pleos/vehicle/VehicleReadModels.kt: 읽기 값/시각/오류/영역별 범위와 SafeParkingCheck 구현.
- PleosVehicleReadBackend.kt: 실제 Pleos/AAOS 읽기 전용 어댑터. 항목별 응답 제한, 부분 실패, null/비정상 값 처리. 설정값·좌표·프로필 원문 로그 없음.
- integration/LiveVehicleReadIntegration.kt: 기본 비활성. 전경에서만 폴링하고 배경/끄기/종료 시 read 취소 완료 후 SDK와 Car 연결 해제. 값·주차 판단도 초기화한다. 값은 저장하지 않는다.
- ui/vehicle/VehicleReadPanel.kt 및 MainActivity/ViewModel/화면 연결: 독립적인 실제 차량 조회 카드, 권한/오류/만료 표시. 기본 시뮬레이션과 구분하며 시뮬레이션 동의가 차량 제어를 실행하지 않는다.
- DriveMateAppGraph: 기존 DriveMateSession 진입점에 B SafeParkingCheck를 주입하는 경계만 추가했다. 실제 내비 모드에서 사용하고 시뮬레이션의 Fake 주차 포트는 유지한다.

SecurityException은 권한 거부, 미지원 AAOS 속성은 미지원, 시간 초과/잘못된 값/기타 오류는 각각 표시한다. 설치 Vehicle AAR에서는 문서의 VehicleException이 난독화돼 있어 비공개 난독화 메서드나 메시지 파싱에 의존하지 않는다. 따라서 SecurityException 외 SDK 실패의 세부 권한/미지원 분류는 서비스 오류로 표시될 수 있다. 확인되지 않은 errorCode 접근을 만들어 사용하지 않았다.

## 안전 주차 경계

유효한 최신 속도의 절댓값이 0.05m/s보다 크거나 기어가 D/R/N이면 NOT_PARKED다. 후진 속도는 음수이므로 절댓값을 사용한다. 기어 1~9는 전진으로 분류한다. 신호는 최대 5초, 미래 시각·권한 오류·미수신·NaN 등은 주차 근거로 사용하지 않는다.

P단, 속도 0, 주차 브레이크 true여도 장소의 안전/합법 주차를 입증하지 못한다. 따라서 이번 VehicleSafeParkingCheck는 VERIFIED를 반환하지 않고 그 밖의 조건은 UNKNOWN이다. 휴식 타이머나 좌석 조정 권한을 신호만으로 해제하지 않는다. 페달을 밟은 상태와 주차 브레이크 체결도 구분한다.

## 직접 확인

1. 최신 앱을 실행하고 ‘실제 차량 상태 조회 · 제어 없음’ 카드에서 ‘차량 상태 조회하기’를 누른다.
2. 속도 조회 권한을 원하는 경우 허용한다. 거부해도 다른 값과 오류를 볼 수 있다.
3. 기어·속도·주차 브레이크·페달·공조 영역·창문 원시 값을 확인한다. 배경으로 갔다 돌아오면 재조회한다.
4. 카드의 조회 끄기를 누르면 값이 비워져야 한다. 차량 설정이 바뀌지 않아야 한다.

## 검증 기록

단위 테스트는 P+0+주차 브레이크를 안전으로 올리지 않음, 전/후진·기어, 오류/오래된/미래 값, 명시적 활성화, 배경 취소/재연결, 부분 실패, 전체 시간 초과를 확인한다. 실제 SDK smoke는 제어 없이 읽고 해제한다. 화면 검사는 격리된 fixture를 사용한다.

초기 실행: 단위 399개 실패/오류 0, lint 오류 0/경고 25. 신규 계측 3개 통과(11.908초). 실제 기어·주차 브레이크 수신, 공조 capability 2영역·창문 capability 4영역 확인. 속도는 PERMISSION_DENIED이며 속도 값 수신 성공으로 기록하지 않는다. 후속 최종 검증은 아래 기록한다. 실차·수동 설정 변경 감지·설정 적용/복원은 미검증/미구현이다.
