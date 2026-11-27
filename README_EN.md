# TickHub

> Turn your phone into a reliable GitHub Actions scheduler · 把手机变成 GitHub Actions 的可靠调度中心

English · [简体中文](README.md)

Replace GitHub's unreliable `on.schedule` with an Android phone: sign in to GitHub → scan repositories and workflows → configure trigger rules (time + recurrence) → your phone fires `POST /dispatches` on time via system alarms. Includes network waiting, missed-trigger catch-up, OEM ROM background resilience, real run-duration tracking and result notifications.

## Status

| Item | Status |
| --- | --- |
| Version | **v1.3.4 stable** (signed APK, upgrade-compatible) |
| Spec / architecture | ✅ [`docs/TickHub-开发需求文档.md`](docs/TickHub-开发需求文档.md) (Chinese) |
| CI | ✅ GitHub Actions: build, unit tests, signing, Release publishing |
| Login | ✅ GitHub OAuth Device Flow (built-in public Client ID, zero setup) |
| Scan & manage | ✅ Repo / workflow scan, persistent selection, manual trigger |
| Rules | ✅ 6 recurrence types (once / daily / weekly / legal workday / legal holiday / every N days), multi-slot, random jitter |
| Scheduler | ✅ Exact alarms + network wait + dispatch + catch-up + reboot re-registration |
| Device acceptance | ✅ Verified overnight-to-next-morning on-time triggers, no Doze sleep; reboot & background-kill resilient |
| Native schedule | Disabled by repo-side automation; not duplicated in the app |
| Size / power | ✅ ~10 MB APK (smaller after R8 shrink); result polling at 5s intervals (stops on completion, no residual wake-ups) |

## Why

GitHub Actions `on.schedule` on free tiers is frequently delayed or skipped during peak hours — bad for scheduled check-ins or recurring pulls. TickHub puts scheduling back on your own device: a system-level exact alarm wakes the phone, waits for network readiness, then calls GitHub's `workflow_dispatch` API, with retries, offline waiting, catch-up for missed runs, and real execution-result tracking.

## Tech stack

- **Language**: Kotlin 2.4 (100% Kotlin, no legacy Java)
- **UI**: Jetpack Compose + Material 3, MIUI-style typography and palette
- **Architecture**: Single Activity + state-driven Compose screens; `AppViewModel` (StateFlow) as single source of truth; Repository layer for data access
- **Scheduling**: `AlarmManager.setExactAndAllowWhileIdle` + `BroadcastReceiver` + `WorkManager` (execution & result tracking)
- **Storage**: DataStore Preferences (config / ordering / rules) + Android Keystore (encrypted token)
- **Networking**: OkHttp + kotlinx.serialization (no redundant Retrofit dependency)
- **Build**: Gradle 9.7 / AGP 9.3.1; release enables R8 code/resource shrinking with complete keep rules (obfuscation off for readable stacks + safe serialization), producing both APK and AAB

~4,800 lines, clearly layered: `data` (models / storage) → `domain` (rule engine) → `network` (API) → `repository` → `scheduler` → `ui`.

## Install

Download the latest signed APK from [Releases](https://github.com/LucasLu-97/TickHub/releases) and install over the existing version (same signing key). Minimum Android 8.0 (API 26).

## Permissions

For reliable scheduling the app needs:

- **Auto-start** (mandatory on Chinese OEM ROMs, otherwise alarms die after swipe-away)
- **Battery optimization exemption** (unrestricted battery)
- **Exact alarm** permission (Android 12+)
- **Notification** permission (Android 13+, for result notifications)

The app only sends HTTPS requests when a trigger fires. Business notifications are still delivered by each repo's existing push channel. Tokens are encrypted in Keystore — never logged, never uploaded.

## Usage

1. **Sign in**: Open the app → "Sign in with GitHub" → enter the device code in the browser and authorize → return to the app (a brief "network unreachable, retrying" during the background switch is normal and self-resolves). The authorization page shows "GitHub CLI" as the app name — expected, because the official public Client ID is reused.
2. **Grant permissions**: In Settings, enable battery exemption, auto-start, and exact alarms.
3. **Scan repos**: Repos tab → "Scan repos & workflows" → expand a repo → select workflows to manage. Tap "Trigger now" to verify the link.
4. **Create a rule**: Rules tab → New → select a repo and its workflows (cross-repo multi-select is split into per-repo rules) → set trigger time(s) → pick recurrence → optional jitter → save.
5. **Verify**: Use "Trigger now" to confirm a success entry in History, then create a rule a few minutes ahead to test automatic firing.
6. **Troubleshoot**: If a trigger misses, Settings → Debug log → "Copy all" to export logs (alarm registration/firing, network wait, each dispatch attempt's HTTP code and error). Logs contain no tokens or device codes.

### Card gestures

- **Swipe left** → red delete icon appears (tap to confirm; tap the card to cancel).
- **Long press** → multi-select mode with "Select all / Delete / Cancel".
- **Long-press drag handle** → lift and reorder (repo order globally; within-group order for rules), persisted locally.

## Build

Everything builds in GitHub Actions — no local Android SDK needed:

```bash
git tag v1.3.0 && git push origin v1.3.0
```

Actions runs unit tests → builds an R8-shrunk signed **APK** → a store-ready **AAB** → `mapping.txt` → publishes a Release. You can also trigger `workflow_dispatch` manually for an artifact. The signing key lives in repo Secrets, valid until 2054.

Local build (Android SDK required):

```bash
./gradlew assembleRelease bundleRelease \
  -PversionName=1.3.0 -PversionCode=130 \
  -PgithubClientId=<your GitHub OAuth Client ID>
```

## License

Open-sourced under the [Apache License 2.0](LICENSE) — commercial use, modification and redistribution allowed with attribution. The bundled OAuth Client ID is GitHub's official public value (not a secret); inject your own at build time via `-PgithubClientId` if needed.

## Publishing to app stores

Each Release ships an **AAB** (`TickHub-vX.Y.Z.aab`) ready for Google Play (AAB required for new apps since 2021). Before upload, complete the Play Console items: privacy policy URL, Data Safety form, content rating, and target audience. `mapping.txt` is included for deobfuscating crash reports. Chinese stores generally accept the signed APK.

## Documentation

Full spec, architecture, data model, API contracts, risks and handover guide: [`docs/TickHub-开发需求文档.md`](docs/TickHub-开发需求文档.md) (Chinese).
