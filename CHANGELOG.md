# Changelog

## 2026-04-08 — Native upgrade foundations (feature/android-native-upgrade)

### Added
- Workspace subsystem with SAF import/export support and persisted grants management:
  - `WorkspaceManager`, `WorkspaceRegistry`, workspace permission modes (`read-only`, `ask-before-write`, `full access`).
  - `WorkspaceManagerActivity` for importing folders/zip, listing recent workspaces, revoking grants, exporting ZIP.
- Terminal subsystem with guarded command execution:
  - `TerminalSessionManager` and `TerminalCommandPolicy`.
  - `TerminalActivity` with run/interrupt/restart/clear/copy/paste controls.
- Diagnostics subsystem:
  - `EnvironmentManager` and `EnvironmentDiagnosticsActivity`.
  - Runtime report generation, env restart/rebuild actions, cache cleanup, persistent log access.
- Integration capability center:
  - `IntegrationCapabilityManager` and `IntegrationStatusActivity` for WhatsApp/SMS/browser/Facebook/Canva/notification readiness.
- Onboarding dashboard:
  - `OnboardingDashboardActivity` summarizing setup, integrations, and workspace readiness.
- Home + Settings quick-access wiring:
  - Main actions for push-to-talk, workspace, terminal, diagnostics.
  - Settings “Native Tools” section with navigation and default workspace mode selector.

### Updated
- `AssistantActivity` now supports explicit push-to-talk launch intent (`start_push_to_talk`).
- `AriaNotificationListenerService` now records lightweight notification bridge scaffold state.
- `file_paths.xml` now exposes cache files for ZIP sharing through `FileProvider`.
- CI workflow now runs `lintDebug`, `testDebugUnitTest`, and `assembleDebug` on all push branches.
- Added architecture and rollout document: `docs/android-native-upgrade-plan.md`.

### Tests
- Added JVM tests for:
  - workspace permission and policy decisions,
  - terminal command policy,
  - terminal session execution behavior.
