# AGENTS.md

Guidelines for AI coding agents working on `netease-ios`.

## Project Overview

`netease-ios` is an Android utility (**Kotlin + Jetpack Compose, Material 3**) that prepares iOS-role login (`src_client_type = 2`) for official NetEase Android games in a single pass via UniSDK (`GMWebviewActivity`) and MPay (`loadMpaySession`).

- **Application ID / Namespace**: `me.naptila.neteaseios`
- **SDK**: `minSdk = 28`, `compileSdk = 37`, `targetSdk = 37`
- **Toolchain**: AGP `9.5.1`, Gradle `9.6.0` (Daemon JVM `25`), Kotlin `2.2.10`, Compose BOM `2026.02.01`
- **Tests**: Unit and instrumented test suites are intentionally omitted. Do not re-add `app/src/test`, `app/src/androidTest`, or test dependencies unless explicitly requested.

## Source Layout

- `app/src/main/java/me/naptila/neteaseios/`
  - `MainActivity.kt` - Hosts `BridgeUiState`, manages `MpayBridgeSession` and SAF `ACTION_OPEN_DOCUMENT_TREE` grants, and persists per-app preferences (`netease_mpay_bridge`).
  - `bridge/BridgeProtocol.kt` - `GameSpec`, MPay protocol constants, and package/UID/`game_id` validation.
  - `bridge/GameTarget.kt` - Installed launcher app enumeration, `LruCache` app icon loader, and target path/URI helpers.
  - `bridge/SeedStorage.kt` - Manual dual-mode seed writer (`STANDARD` via `.widget_file_provider`, `SAF_MEDIA` via SAF `Android/media`), per-game preferences (`seed_mode_<pkg>`, `seed_non_empty_token_<pkg>`), pre-launch readiness checks, primary-storage path resolution, and temp-file cleanup.
  - `bridge/MpayBridgeSession.kt` - Single-pass `127.0.0.1:0` HTTP server (`/page`, `/prepare`, `/submitted`, `/failed`), `GMWebviewActivity` launcher, seed lifecycle, and callback status handling.
  - `bridge/BridgePage.kt` - Self-contained HTML/JS bridge page rendered inside `GMWebviewActivity` (`gameContextInfo` -> `/prepare` -> `loadMpaySession` -> `/submitted` or `/failed`).
  - `bridge/HttpRequest.kt` - Bounded HTTP/1.1 parser (8 KB header limit, 4 KB body limit).
  - `ui/BridgeScreen.kt` - Main screen, searchable installed-app picker dialog, status banner, and unified preparation card.
  - `ui/AppIcons.kt` - Custom Compose `ImageVector` icons (no `material-icons-extended` dependency).
  - `ui/theme/` - Material 3 light/dark color schemes and typography.
- `.github/workflows/release.yml` - Tag-triggered Release APK build and GitHub Release workflow.

## Architecture & Security Invariants

1. **Zero Credential Exposure & Per-Game Seed Token Strategy**
   - Never collect, transmit, log, or persist passwords, SMS OTPs, cookies, active MPay tokens, or account UIDs to disk.
   - Default seed `"token"` MUST remain `""` (`nonEmptyToken = false`): games like Sky and Identity V check `!= null` in `LoginStore` while `TextUtils.isEmpty("")` blocks network validation locally, preventing server `1311` (`LOGOUT_DEVICE`) store wipes. Only send placeholder `"token": "0"` when the per-game `nonEmptyToken` toggle is explicitly enabled by the user (for newer MPay SDKs such as Onmyoji `2.8.94+` whose `LoginStore` checks `!TextUtils.isEmpty`). Always zero byte buffers (`bytes.fill(0)`, `request.body.fill(0)`) in `finally` blocks.
   - Keep `netease_mpay_bridge.xml` excluded in `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`. Do NOT set `FLAG_SECURE` on `MainActivity` (screen capture/recording must remain allowed, and the UI displays no credentials).

2. **Loopback Server & Lifecycle Hardening**
   - Bind `ServerSocket` exclusively to `127.0.0.1` on port `0` with short socket timeouts; verify `isLoopbackAddress`, `Host`, `Origin` (when present), and the 24-byte hex nonce prefix (`/bridge/<nonce>/`).
   - Keep `android:configChanges="keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize"` on `MainActivity` in `AndroidManifest.xml` so switching to landscape games does not destroy the active loopback session.

3. **Game-Agnostic Scope & Transport Rules**
   - **Game discovery**: The app picker lists only installed launcher apps whose package starts with `com.netease.`, as explicitly requested for this assistant. Do not infer SDK support from package prefix, game label, signatures, `versionCode`, or `game_id`. Read `game_id` dynamically from `gameContextInfo` during `/prepare`.
   - **Recommended default transport (no auto-fallback)**: Keep target platform fixed to iOS (`IOS_CLIENT_TYPE = 2`) and seed login type fixed to `DEFAULT_LOGIN_TYPE = 1`. Without a saved per-game override, `com.netease.onmyoji` defaults to `SeedTransportMode.SAF_MEDIA` (via SAF `Android/media`), and other selectable games default to `SeedTransportMode.STANDARD` (`<pkg>.widget_file_provider`). Keep a manual override available; never automatically fall back to another transport on failure. The `nonEmptyToken` (`""` vs `"0"`) setting stays in a separate compatibility dialog and remains opt-in.
   - **SAF boundaries & cleanup**: `loadMpaySession` reads via `FileInputStream(path)`; always pass a resolved primary-storage filesystem path (never a `content://` URI or hardcoded `/storage/emulated/0`). Restrict SAF tree grants to `com.android.externalstorage.documents` (`primary:Android/media` or `primary:Android/media/<pkg>`), and only delete recorded `ntes_mpay_<32hex>.tmp` URIs - never scan or wipe game directories.
   - **SDK callback states**: Only treat explicit `errCode == 0` as SDK-confirmed (`submitted`); treat missing WebView callbacks as `unconfirmed` (pending user verification in-game), never as failure or fake confirmation.

4. **UX & Implementation Guardrails**
   - **Stale account popup warning**: If a game's account-switcher popup was already open prior to `loadMpaySession`, tapping a stale entry causes infinite spinning (or `1311` when `nonEmptyToken` is enabled). Preserve the prominent warning instructing users to close an already-open popup via the top-right `x` button and reopen it before logging in.
   - **Compose icon state**: In `AsyncGameAppAvatar`, keep `remember(packageName) { mutableStateOf(cachedIcon) }` + `LaunchedEffect(packageName, cachedIcon)`. Do not replace with `produceState`, which does not reset `value` on key change.
   - **HTTP header splitting**: In `HttpRequest.kt`, keep `.removeSuffix("\r\n\r\n").split("\r\n")` so Kotlin `String.split` does not produce trailing empty header lines.

## Build & Repository Conventions

- Build commands: `./gradlew assembleDebug` and `./gradlew assembleRelease` (uses JDK `25` per `gradle/gradle-daemon-jvm.properties`).
- Keep AGP 9.5 DSL conventions in `app/build.gradle.kts` (`compileSdk { version = release(37) }`, `optimization { enable = false }` on `release` to prevent R8 Compose/Coroutines stripping crashes, and optional `APP_VERSION_*` / `RELEASE_STORE_*` env vars for CI releases).
- Keep dependencies minimal (`gradle/libs.versions.toml` only unless strictly necessary).
- `tmp/` is gitignored local scratch/handoff space; never stage or reference files under `tmp/`.
