# B4-2 직접 동의하는 차량 설정 변경

이번 구현은 **B 화면에서 직접 검토·동의하는 공조 전원과 창문 절대 위치값 변경**이다. 운전자 피로 판단/음성 질문/자동 환기는 구현하지 않았고 기존 A VehicleController 연결은 시뮬레이션 그대로다. A 코드나 공통 contract를 바꾸지 않았다. 좌석·조향·제동·긴급 신고 기능은 추가하지 않는다.

## 실제 SDK 범위

Vehicle 2.3.2 / 에뮬레이터 Vehicle Service 2.3.22(code74)을 유지한다. Gradle·SDK 버전은 바꾸지 않았다. 설치 AAR의 실제 함수/콜백과 [공식 Vehicle API 구조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/obtain-domain-object), [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission)을 대조했다.

- 공조 전원: checkHvacPowerCapabilities → getHvacPowerState → setHvacPower. HvacPowerStateListener 등록/해제.
- 창문: checkWindowPositionCapabilities → getCurrentWindowPosition → setWindowPosition. WindowPositionListener 등록/해제.
- 공통: 읽기·쓰기가 함께 가능한 capability, 읽을 수 있는 원본, 앱의 실제 제어 권한을 모두 확인한다. set 직전에도 권한을 재검사한다. 권한 없는 항목이나 원본 미수신 영역을 목표로 내놓지 않는다.
- 추가 권한은 CONTROL_CAR_CLIMATE/CONTROL_CAR_WINDOWS 두 개뿐이다. 기존 조회 권한과 android.car 라이브러리는 유지한다. SDK 바이너리는 커밋하지 않는다.
- 창문은 SDK가 제공한 범위 내 정수 절대값만 받는다. 위치값을 ‘조금 열기’, 백분율, 열림 방향으로 추정하지 않는다. 개발 검증용 값 지정이며 자동 환기 제안으로 사용하지 않는다.
- 온도 설정은 제외했다. 제어 권한이 부여된 상태에서도 기존 getHvacTemperature(CELSIUS)에서 두 영역 모두 어댑터 분류 SERVICE_UNAVAILABLE을 재현했다. 정상 값이 확인되지 않아 원본 캡처·온도 변경·복원을 제공하지 않는다. 난독화된 SDK 예외를 추정 호출/문자열 파싱해 성공으로 바꾸지 않았다. 정확한 공급자 오류 코드/원인은 추가 확인 대상이다.

## 동의·원본·복원

설정 변경 준비는 조회/알림 등록만 한다. 준비한 프로필의 현재 세션에서 최신 P단·속도 절댓값 0.05m/s 이하·주차 브레이크 체결이 모두 있어야 목표를 검토할 수 있다. 각 신호와 설정은 5초 이내여야 한다. 이 조건은 이번 개발 기능을 제한하는 정차 신호이며 장소의 안전성을 보증하거나 SafeParking.VERIFIED를 반환하는 조건이 아니다. 속도 권한 거부 시 변경을 허용하지 않는다.

검토마다 새 UUID를 발급하고 대상·원본·목표·프로필·연결 세대·변경 revision·30초 만료를 묶는다. 확인창을 여는 것과 취소는 setter를 호출하지 않는다. 정확한 현재 검토의 확인 버튼만 요청을 실행하며 사용한 ID는 재사용하지 않는다. 시뮬레이션 음성 동의는 이 모듈에 전달되지 않는다.

확인 후 원본과 정차 신호를 다시 읽는다. 값이 달라졌으면 동의를 폐기한다. 원본을 메모리에 먼저 보관한 뒤 한 항목만 변경한다. SDK callback 반환만으로 성공 처리하지 않고, callback의 영역/목표 일치와 다시 읽은 실제 값·신선도·지원 범위·정차 신호를 확인한다.

복원은 해당 세션에서 확인한 앱 소유 변경만 대상으로 하고 **새 UUID의 별도 확인창과 새 동의**를 요구한다. 복원 직전 현재 값이 앱이 적용한 값과 같아야 한다. 최초 변경 동의로 복원하지 않으며 여러 항목을 묶어 자동 롤백하지 않는다.

한 항목에서 오류/시간 초과/취소/읽기 불일치가 나면 요청이 일부 적용됐을 가능성을 남기고 복원 권한을 취소한다. 자동 재시도나 자동 원상 복구는 없다. 새 준비 연결을 시작할 때 이전 복원 기록을 폐기하므로 필요한 원상 조정은 사용자가 차량에서 확인해야 한다.

## 변경 알림과 한계

SDK listener로 관찰한다. 진행 중인 요청과 일치하지 않는 값/오류, 또는 완료 후 앱 소유 항목에 들어온 알림은 외부 또는 출처 미확인 변경으로 간주해 복원을 막는다. 단순 주기 폴링만으로 수동 변경이라고 단정하지 않는다. 최초 등록 시 현재 값과 같은 알림은 정상 기준값으로 취급한다.

SDK 알림에는 조작 주체/요청 ID가 없어 실제 수동 조작과 다른 앱·늦은 자체 알림을 완전히 구분하지 못한다. 완료 후 적용값과 같은 중복 알림은 복원 권한을 유지한다. 다른 값 또는 미확인 알림을 받으면 복원을 차단하며, 이후 적용값으로 돌아와도 권한을 다시 부여하지 않는다. 진행 중 목표와 정확히 같은 수동 조작이나 SDK가 알림을 내지 않는 동일 값 재설정 의도까지 식별한다고 보장하지 않는다. 이 경우에도 복원에는 현재 값 확인과 새로운 명시적 동의가 필요하다.

정차 신호 읽기와 SDK setter는 원자적 동작이 아니다. 실행 직후 차량 상태가 바뀌는 경쟁 상황을 SDK/차량의 안전 제한 밖에서 완전히 차단할 수 없다. 실차 검증 없이 주행 중 지원으로 확대하지 않는다.

배경 전환/조회 끄기/제어 연결 닫기/프로필 편집·전환/앱 종료는 즉시 새 명령 권한을 폐기하고 진행 작업 취소 후 listener/SDK를 해제한다. 이미 서비스에 전달된 요청은 취소로 되돌아간다고 가정하지 않는다. 원본·동의·복원 기록은 저장소나 프로필에 저장하지 않는다. 앱이 다시 열려도 자동 복원하지 않는다.

## 사용 방법

1. 프로필을 선택하고 기존 ‘차량 상태 조회하기’에서 속도 권한을 허용한다.
2. 실제 조회 결과에서 P단·속도 0·주차 브레이크 체결을 확인한다. 미수신/권한 거부 상태는 변경 불가다.
3. ‘설정 변경 준비 · 조회만’을 누른다. 이 버튼은 차량 설정을 바꾸지 않는다.
4. 공조 전원 또는 특정 창문 위치값의 변경 내용을 검토한다. 확인창에서 **실제로 변경할 의사가 있을 때만** ‘동의하고 변경’을 누른다.
5. 차량과 앱에서 실제 값을 확인한다. 복원 가능한 기록이라면 ‘원래 값 복원 내용 검토’ 후 별도 동의한다. 복원 불가/미확인 시 차량에서 직접 상태를 확인한다.

자동 테스트에서는 실제 setter를 호출하지 않았다. 직접 테스트 전 실행 조건과 대상 값을 확인해야 한다. 좌석 설정과 주행 중 자동 환기는 이번 테스트 대상이 아니다.

## 변경 파일

- pleos/vehicle/: 원본/목표/범위 모델, ConsentedVehicleSettings, 실제 PleosVehicleSettingsBackend. 기존 조회 모델에는 READ_WRITE 근거만 추가.
- integration/LiveVehicleControlIntegration.kt: 기본 비활성, 비동기 작업 직렬화/배경 취소/세션 권한 폐기.
- UI/ViewModel/MainActivity: 준비·검토·변경·별도 복원·연결 닫기, 새 기능의 실제/시뮬레이션 구분.
- Manifest: 확인한 두 제어 권한.
- 단위/격리된 화면/실제 조회·구독 smoke 테스트. 실제 제어 SDK 경로와 Fake 테스트를 구분한다.

## 검증

마지막 입력 검증 보강: SDK의 Int 위치값과 일치하도록 1.0000001 같은 작은 소수도 목표값에서 제외했다. assembleDebug/testDebugUnitTest/lintDebug를 다시 실행해 BUILD SUCCESSFUL(48초), 414개 실패/오류 0, lint 오류 0/경고 25 확인 후 최신 APK를 설치했다. 이 보강은 단위 검사로 확인했으며 앞선 native 조회/구독과 화면 계측을 다시 실행하지 않았다.

최종 빌드: assembleDebug/testDebugUnitTest/lintDebug/assembleDebugAndroidTest BUILD SUCCESSFUL(38초). 단위 414개 실패/오류/건너뜀 0, lint 오류 0/경고 25. 전체 계측 18개 통과 후 권한 재검사를 추가한 최종 APK에서 핵심 조회·구독/동의 UI 3개를 다시 실행해 통과했다(17.362초). 공조 전원 1·창문 4개 대상, 알림 8회·실패 0·native write 0 재확인. 새 초기 카드 배치를 캡처로 확인하고 실제 조회/제어 비활성 상태로 앱을 열어두었다. 사용자 프로필과 키 파일은 유지했다.

- 단위 테스트: 최초 동의와 복원 동의 분리, 재사용/오래된/틀린 ID, 달라진 원본, 정차 신호 부족, 수동/출처 미확인 알림, readback 불일치, 취소·실패·프로필 변경, 알림 등록 실패 시 권한 폐기, 창문 범위/정수 제한 포함.
- 전체 단위 414개 통과. lint 오류 0, 경고 25. 최종 빌드 기록은 아래 후속 항목 참조.
- 전체 에뮬레이터 계측 18개 통과(63.085초): 기존 B1~B4 조회 회귀와 새 동의/복원 UI 및 설정 조회·구독.
- 실제 SDK에서 공조 전원 READ_WRITE 1영역, 창문 READ_WRITE 4영역, 변경 알림 8회/실패 0 확인. 제어 권한 두 개 모두 granted=true 확인. native setter 실행은 0회다.
- 실제 속도 권한은 여전히 거부 상태였고 온도 값은 미수신이다. 실제 변경/복원·수동 조작과 경쟁 상황·실차 검증은 미실시다. 지원 항목 조회 성공을 실제 제어 성공으로 기록하지 않는다.

## 2026-11-11 후속 확인

- 최신 assembleDebug/testDebugUnitTest/lintDebug/assembleDebugAndroidTest 빌드 성공(57초). 단위 테스트 XML 합계 416개, 실패/오류 0.
- 영역별 관측 시각을 유지해 늦게 끝난 다른 영역 조회가 오래된 원본을 새 값으로 보이게 하지 않도록 보강했다.
- 최신 앱/계측 APK 설치 후 읽기 전용 계획 확인 테스트 1개 통과(3.35초). PARK, 속도 조회 오류 없음, parkingBrake=false. nativeWrites=0.
- 속도 권한 거부라는 위 기록은 과거 상태다. 현재 속도 권한은 허용됐지만 주차 브레이크 미체결 때문에 승인받은 실제 공조/창문 변경·복원 검증은 실행하지 않았다.
- car_service help는 현재 user 빌드에서 SecurityException으로 제한됨을 확인했다. 권한 제한 우회나 정차 조건 완화는 하지 않았다.


## 2026-11-12 에뮬레이터 검증 제약

- 기존 Pleos_Connect_v2.1.0 AVD를 데이터 초기화 없이 재실행했다. 부팅 완료 후 읽기 전용 계획 검사 1개 통과(2.027초): gear=PARK, parkingBrake=false, speedProblem=null, nativeWrites=0. 이는 변경·복원 테스트 성공이 아니다.
- 별도 에뮬레이터 창에서 Extended Controls를 실제 열어 확인했다. 현재 메뉴에는 Displays/Cellular/Battery/Location/Phone/Directional pad/Microphone/Fingerprint/Virtual sensors/Bug report/Snapshots/Record and Playback/Settings/Help가 보이며 Car data는 없다.
- Pleos Virtual Console의 Telltales/Parking Brake는 표시등이다. 사용자가 On을 선택한 후에도 앱의 PARKING_BRAKE_ON은 false였다. 표시등을 체결 근거로 사용하지 않는다.
- Android 공식 문서의 VHAL Car data 조정 경로는 현재 AVD UI에서 사용할 수 없었다. car_service help 역시 앞선 확인에서 user 빌드 제한으로 거부됐다. root, 권한 우회, 정차 조건 완화, 브레이크 setter는 적용하지 않았다.
- 남은 실제 공조·창문 변경/별도 동의 복원 검사는 체결 신호를 공급하는 공식 Pleos 테스트 방법이 확인된 환경에서 수행한다. 지금 상태를 B4 완료로 표시하지 않는다.
- 공식 근거: https://developer.android.com/training/cars/testing/emulator 및 https://document.pleos.ai/en/docs/pleos-only/app-test-guide/virtual-console-guide
