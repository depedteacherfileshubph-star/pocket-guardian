# Pocket Guardian

An Android app for a child's phone. A parent or guardian sets up a **visible, consent-based** way for the child to ask for help: a volume-button sequence (while the app is open), a big on-screen SOS button, and an optional Quick Settings tile. When triggered it can SMS trusted contacts with the child's location, call one contact, and/or sound an alarm.

Status: builds and passes unit tests (trigger logic). **Everything that touches real SMS, calls, GPS, buttons, lock screen and accessibility must be verified on a real phone** (see `docs/TEST_PLAN.md`).

## What it does NOT do (by design)

- By default the volume shortcut works only while the app is open. An **optional, visible "Screen-off shortcut"** mode extends it to the screen-off/lock-screen case (see below). It is never hidden, it is not guaranteed on every phone, and it only counts as working after a real screen-off test passes. The app does **not** use an Accessibility Service.
- The **Power button** is not used.
- It does **not** support the **Power button**. Android restricts it and it varies by device.
- It does not block or change normal volume behaviour (key events are passed through).
- It never records audio, never hides itself, never tracks location continuously, has no accounts and no analytics. The only internet use is the optional GitHub update check (below).

## Installation steps for a guardian

You need: the child's phone (Android 8.0 / API 26 or newer, with a working SIM that can send SMS), and a PC to install from.

1. **Build or get the APK.** Open this folder in Android Studio (Ladybug or newer) and press *Build > Build APK(s)*, or run `gradlew assembleDebug` (JDK 17). The file is `app/build/outputs/apk/debug/app-debug.apk`.
2. **Install on the child's phone.** Enable *Developer options > USB debugging*, connect the phone and run `adb install -r app-debug.apk` (or copy the APK to the phone and open it; allow "install unknown apps" for your file manager for this one install, then switch that setting off again).
3. **Open Pocket Guardian** on the child's phone. Tap **Guardian settings** and **create a PIN** (4-8 digits). Keep it private.
4. **Trusted contacts** (Settings > "3. Trusted contacts and tests"): add up to 5 people. Press **Send a test message** (you confirm first; it sends one real SMS marked as a test) and/or **Test call**, then confirm that the person got it. Contacts show "Tested and confirmed".
5. **SOS actions** ("2. SOS actions"): choose SMS, call, alarm. Allow each permission when asked; the screen explains why. Edit the help message. Choose the cancel time (default 5 s; 0 = send immediately).
6. **Trigger** ("1. Trigger"): choose the button pattern, number of presses and time window, then **practise in the test box** (nothing is sent).
7. Switch **SOS enabled** on. The **Setup check** must say *SOS is ready*. Fix anything it lists.
8. **Explain to the child:** the volume shortcut only works while the Pocket Guardian screen is open and the phone is awake; otherwise use the big SOS button (or the tile). They can always cancel during the countdown. They should also know to call the local emergency number.
9. *(Optional)* **Screen-off shortcut**: Guardian settings > "Screen-off shortcut". Allow notifications, switch it on, then run **Start 60-second test** (turn the screen off, press the sequence, feel two buzzes). Also allow full-screen notifications (Android 14+) and set battery to *Unrestricted*. The status shows "Tested" only after a passing test. Re-test after phone updates.
10. *(Optional)* Add the **Quick Settings tile**: swipe down twice, tap the pencil/Edit, drag "Pocket Guardian" into the active tiles.
11. Battery: in phone Settings, avoid battery-saver restrictions for Pocket Guardian, and keep the phone charged. Re-run the contact tests monthly.

Forgot the PIN? Phone Settings > Apps > Pocket Guardian > Storage > Clear data (this erases all settings and contacts).

## Device and Android-version limitations (short version)

| Capability | Support | Notes |
|---|---|---|
| Volume sequence, app open and screen on | Android 8-15 (API 26-35). Uses standard `Activity.dispatchKeyEvent`. | Real-device test needed per model; some phones/accessories may remap volume keys. |
| Volume sequence, screen off / lock screen / other app | **Optional "Screen-off shortcut" mode, device-dependent, real-device test required** | Uses a visible foreground service + MediaSession remote-volume provider (Android 8-15). Volume keys are still forwarded to the system volume. Routing differs per phone/OEM; some never deliver keys with the screen off. Stops after reboot/kill until the app is opened. Needs notification permission; Android 14+ may need the full-screen-notification permission for the SOS screen to pop up. Holding a key counts once. Without it the sequence works only while the app is open. |
| Power button | **Not supported** | Restricted and device-specific. Treat as unavailable. |
| On-screen SOS button | Android 8-15 | Opens a visible countdown screen; can show over the lock screen when started from the app (`showWhenLocked`, API 27+). |
| Quick Settings tile | Android 8-15 | User must add it. Locked phones usually require unlocking first. Real-device test needed. |
| SMS | Needs SIM, signal, `SEND_SMS` | "Sent" means handed to the network, not delivered/read. Dual-SIM uses the default SMS SIM. |
| Direct call | `CALL_PHONE`; otherwise opens the dialer | |
| Location | One-shot fix when SOS runs; falls back to a cached position <= 15 min old (labelled) or says "unavailable" | Needs Location permission and the phone's location setting on. Indoors can fail. |
| Alarm | Uses the alarm stream at full volume until stopped | Do-Not-Disturb "alarms" settings may affect it. |
| Phones without telephony (most tablets) | SOS reports it cannot send | |

Plain-English version is also inside the app: Guardian settings > Privacy and device limits.

## Updates from GitHub

The app can check this project's GitHub Releases and install a newer APK. It is **guardian-initiated** (Guardian settings > App updates; optionally a check when settings open), and Android's installer always asks for confirmation, so nothing installs silently.

Setup (once):
1. Put your repository in `gradle.properties`: `updateRepo=your-github-user/pocket-guardian` (the release workflow also injects it automatically).
2. Create a release keystore (`keytool -genkeypair -v -keystore release.jks -alias pocketguardian -keyalg RSA -keysize 2048 -validity 10000`). **Keep it forever**: phones only accept updates signed with the same key. Install the signed release APK (not a debug build) on children's phones.
3. In the GitHub repo add secrets `KEYSTORE_BASE64` (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
4. Publish a version by pushing a tag: `git tag v1.1.0 && git push origin v1.1.0`. `.github/workflows/release.yml` builds, signs, and attaches `PocketGuardian-1.1.0.apk` plus its `.apk.sha256`.

Safety checks in the app: HTTPS to github.com / githubusercontent.com only; the release must publish a SHA-256 (GitHub's asset digest or the `.sha256` file) and the download must match; the package name and signing certificate must equal the installed app's (Android 9+; Android 8 relies on the installer's own check); the first update needs you to allow "install from this app" once. Privacy: GitHub sees the phone's IP address; nothing else is sent. Use a **public** repository (private releases need credentials, which this app deliberately does not hold). If the GitHub account or keystore is compromised, a bad update could reach phones, so protect both (2FA).

## Distribution note

`SEND_SMS` and `CALL_PHONE` are restricted permissions on Google Play (a permissions declaration is required, and approval is usually limited to default SMS/dialer apps). This project is meant for **sideloading to a family's own phones**. A Play release would need a different design (for example, pre-filled SMS the user sends manually).

## Project layout

- `app/src/main/java/com/safetap/sos/`
  - `MainActivity.kt` - child screen host, key handling (visible activity only), view model
  - `TriggerDetector.kt` - pure, unit-tested press-sequence logic
  - `SosActivity.kt` - visible countdown / cancel / status / fallback guidance
  - `SosEngine.kt` - one-shot location, SMS with sent-status, call, alarm
  - `Device.kt` - permission/battery/airplane/SIM checks and the honest readiness check
  - `Store.kt` - local settings, contacts, PBKDF2-hashed PIN with lockout
  - `Screens.kt`, `SetupScreens.kt`, `Components.kt`, `Theme.kt` - accessible Compose UI
  - `SosTileService.kt` - optional Quick Settings tile
  - `ShortcutService.kt`, `ShortcutScreen.kt` - optional screen-off shortcut (visible foreground service)
  - `Updater.kt`, `UpdateScreen.kt` - guardian-approved GitHub updates
- `docs/TEST_PLAN.md`, `docs/PRIVACY_AND_PERMISSIONS.md`
