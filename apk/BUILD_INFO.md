# 두리두리 — 쉼길 Release APK

- 파일: `두리두리_쉼길.release.apk`
- 패키징일: 2026-11-15 (Asia/Seoul)
- APK를 빌드한 앱 소스 기준: `e85c453f60210d9660b4938043041eb87bfca98b`
- 이 파일을 추가한 제출 커밋은 앱 소스를 바꾸지 않고 APK/README/검증 메타데이터만 추가합니다. 접수 메일에는 해당 제출 커밋의 전체 해시를 기재합니다.
- 앱 이름: 쉼길 / 팀명: 두리두리
- applicationId: `com.example.pleosconnect`
- versionName: `1.0` / versionCode: `1`
- minSdk: 30 / targetSdk: 37
- Release, debuggable=false, testOnly=false
- APK 크기: 22,938,456 bytes (약 21.88 MiB)
- SHA-256: `c48afbc488ea5e561d6c0a471bac2848733f62ba372e836cba4fd2aa5b7bc933`
- 서명: 제출용 별도 키로 APK Signature Scheme v3 서명 및 apksigner verify 성공. 비밀번호/개인키는 저장소 밖의 로컬 비공개 설정으로 보관하며 제출하지 않습니다.
- 정렬: zipalign 16KiB native library page alignment 및 4-byte alignment 검사 성공. 모든 기기의 실행 호환성이나 심사 통과를 보증하는 검사는 아닙니다.
- 실제 실행 시험 환경의 AVD: Pleos Connect v2.1.0 / Android API 34 / x86_64 / 2560×1440 / density 160. 이번 서명 Release APK 자체는 새 기기에 설치/실행하지 않았습니다.

## 빌드 및 검사

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleRelease --console=plain
```

이번 실행은 Debug 빌드/단위 검사 성공(47초), 단위 테스트 XML 합계 474개 실패/오류 0, Release 조립 성공(1분 22초)입니다. Release는 이후 Android SDK build-tools 36.0.0의 zipalign/apksigner로 정렬·서명·검증했습니다. 파일명을 제출 규칙에 맞게 변경한 뒤에도 원래 서명 APK와 SHA-256이 동일합니다.

실제 SDK·차량·음성 계측은 이번 APK 패키징 중 재실행하지 않았습니다. Release에는 개발용 테스트 음/PC 중계 주변 검색이 없습니다. API 키/서명 키/기기 프로필은 포함하지 않습니다. 상세 기능 범위와 한계는 저장소 README의 공모전 제출 안내를 확인해 주세요.
