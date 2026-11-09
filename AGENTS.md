# Codex development instructions

## Project
DriveMate is a Kotlin Android Automotive OS app for the Hyundai Pleos Connect app contest. Read `README.md` and `docs/PROJECT_SPEC.md` (when added) before making changes. The final project specification takes priority for functionality.

## Workflow
- Inspect existing Gradle files and SDK versions before modifications.
- Work on a `feature/*` branch. Target `develop` with pull requests. Keep `main` stable.
- Make small, reviewable changes. Summarize modified files, test results and unverified items in Korean.
- Never report a build/test as passing unless it was actually run.
- Preserve existing working behavior and never guess SDK class names, permissions, version numbers or signatures.

## Architecture
- Prefer Kotlin, MVVM, coroutines/StateFlow and separable domain logic.
- Separate STT/TTS, navigation and vehicle integrations behind interfaces; use clearly labeled Mock implementations for unavailable APIs.
- Implement testable driver states NORMAL, CAUTION, WARNING, HIGH_RISK, RESTING and RECOVERY_CHECK.
- Avoid overlapping TTS playback and STT listening; handle timeout, microphone denial and service failure.

## Safety and privacy
- Never actuate steering/braking, move seats during driving, or automatically contact emergency services solely from unanswered questions.
- Keep driver consent for navigation reroutes and HVAC/window changes; respect manual vehicle-setting overrides.
- Treat STT silence or failure as uncertain, not proof of sleep.
- Never commit secrets, credentials, personal profiles or restricted SDK binaries.
- Do not add external generative AI APIs without an explicit new user requirement.

## Verification
- Run available build/unit tests after each stage; if Android SDK/Emulator is unavailable, clearly report that limitation.
- Distinguish actual Pleos SDK integration from simulator/mock behavior in code and documentation.
