# Android Native Upgrade Plan (feature/android-native-upgrade)

This document maps the **current architecture** and the implementation approach for the native-upgrade pass.

## 1) Current Architecture Inventory (as-is)

### App entrypoints and primary UI
- `app/src/main/java/com/aria/assistant/MainActivity.kt`
  - Home/dashboard surface, quick actions, setup status, root auto-enable action.
- `app/src/main/java/com/aria/assistant/AssistantActivity.kt`
  - Primary chat + voice surface (STT, TTS, automation parse/execute bridge).
- `app/src/main/java/com/aria/assistant/SettingsActivity.kt`
  - Provider, model, TTS, STT, feature toggles, privacy entry points.

### Live voice / session stack
- `app/src/main/java/com/aria/assistant/live/LiveModeService.kt`
  - Foreground live loop, watchdog, STT orchestration, event bus bindings.
- `app/src/main/java/com/aria/assistant/live/core/StreamingSttGateway.kt`
  - STT abstraction + default Android implementation wiring.
- `app/src/main/java/com/aria/assistant/live/core/AndroidSpeechPartialGateway.kt`
  - Android STT partial/final/error/timeout implementation.
- `app/src/main/java/com/aria/assistant/live/core/LiveEventBus.kt`
  - Duplex state/event/command flow between service and UI.
- `app/src/main/java/com/aria/assistant/live/core/PersistentLogger.kt`
  - File-backed rotating app log (`filesDir/aria_persistent_log.txt`).

### AI gateway / model routing
- `app/src/main/java/com/aria/assistant/LettaApiService.kt`
  - Multi-provider chain (Groq/OpenRouter/Gemini/Letta), streaming + fallback.

### Automation / device actions
- `app/src/main/java/com/aria/assistant/VoiceCommandParser.kt`
  - Parse and route safe intent envelopes.
- `app/src/main/java/com/aria/assistant/automation/SafeAutomationExecutor.kt`
  - SMS/WhatsApp/social/browser/Canva/app-launch/hardware action execution.
- `app/src/main/java/com/aria/assistant/automation/SafeAutomationPolicy.kt`
  - Action-level allow/deny policy + confirmation rules.

### Root safety and hardening
- `app/src/main/java/com/aria/assistant/RootSafetyPolicy.kt`
  - Root command allow/deny + strict mode + audit log.
- `app/src/main/java/com/aria/assistant/RootCommandExecutor.kt`
  - Shell execution via `su -c` (timeout + output/error capture).
- `app/src/main/java/com/aria/assistant/SecurityHardeningManager.kt`
  - Security baseline scan/apply automation.

### Notification + privacy
- `app/src/main/java/com/aria/assistant/AriaNotificationListenerService.kt`
  - Notification ingestion + optional read-aloud.
- `app/src/main/java/com/aria/assistant/live/core/DataPrivacyManager.kt`
  - Fine-grained data sharing toggles.

### Build + CI
- Gradle top-level: `build.gradle`, `settings.gradle`
- App module: `app/build.gradle`
- CI: `.github/workflows/android-build.yml`

## 2) Gaps this upgrade addresses

- No dedicated workspace manager for SAF project/folder onboarding.
- No embedded terminal session UX for command-based diagnostics/workflows.
- No structured diagnostic center for runtime/env/tooling observability.
- Capability checks exist in pieces, but not as a consolidated integration surface.
- Push-to-talk launch path from home UI is implicit; no explicit one-tap PTT flow.
- Existing command safety is root-oriented; terminal guardrails and permission modes are missing.

## 3) Planned module additions

- **Workspace & SAF layer**
  - `workspace/WorkspacePermissionMode.kt`
  - `workspace/WorkspaceSecurityPolicy.kt`
  - `workspace/WorkspaceManager.kt`
  - `workspace/WorkspaceRegistry.kt`
- **Terminal & diagnostics**
  - `terminal/TerminalSessionManager.kt`
  - `terminal/TerminalCommandPolicy.kt`
  - `EnvironmentDiagnosticsActivity.kt`
  - `TerminalActivity.kt`
- **Capability center**
  - `integrations/IntegrationCapabilityManager.kt`
  - `IntegrationStatusActivity.kt`
- **UI/manifest wiring**
  - Settings/Main buttons + new screens
  - Manifest activity declarations
  - Additional resource layouts

## 4) Security posture for this pass

- Default workspace mode will be conservative (ask-before-write).
- Terminal command policy blocks clearly destructive operations unless explicitly allowed.
- SAF URI persistence + revoke controls exposed in UI.
- Audit events for policy decisions and diagnostics actions.

## 5) Delivery checklist

1. Add workspace manager + SAF import/export UI.
2. Add guarded terminal + diagnostics center.
3. Add integration capability screen + optional notification status hooks.
4. Wire push-to-talk launch from home surface.
5. Add tests for permission modes/policies/session state/capability mapping.
6. Update CI workflow and docs/changelog.
