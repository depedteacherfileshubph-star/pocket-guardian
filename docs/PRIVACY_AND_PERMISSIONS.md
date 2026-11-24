# Privacy and permissions

## Data held (on the phone only)
| Data | Why | Where |
|---|---|---|
| Trigger settings, action choices, help message | Run SOS | App-private SharedPreferences |
| Trusted contacts (name, number, "tested" flag) | Who to text/call | Same |
| PIN (random salt + PBKDF2-SHA256 hash, 120k iterations) | Protect settings; 5 wrong tries -> 30 s lockout, doubling to 10 min | Same |

Backup and device-transfer of this data are disabled (`allowBackup=false`, `data_extraction_rules.xml`). The app declares `INTERNET`, used **only** by the guardian-initiated update check/download from GitHub (`Updater.kt`, host-restricted to GitHub over HTTPS). No SOS data, contacts, PIN or location ever go over the internet. No analytics, ads or accounts. GitHub can see the phone's IP address when an update is checked.

## Not collected
Audio, camera, photos, contact book, call log, browsing, continuous or historical location. Location is requested **once at the moment SOS runs**, placed in the SMS, and discarded (not stored, not logged).

## Permissions
| Permission | Requested when | Used for | If denied |
|---|---|---|---|
| `SEND_SMS` | Guardian turns on "Send SMS" or opens Contacts | Help SMS and explicit test SMS | No SMS; child screen and Setup check say so; call/alarm still work |
| `ACCESS_FINE/COARSE_LOCATION` | Guardian turns on "Include location" | One-shot fix when SOS runs | SMS still sent, says "Location unavailable" |
| `CALL_PHONE` | Guardian turns on "Call" | Start SOS call immediately | Dialer opens prefilled; someone presses Call |
| `INTERNET` | Always declared | Update check/download from GitHub only | No updates; SOS unaffected |
| `REQUEST_INSTALL_PACKAGES` | Guardian taps Install on a verified update | Hand the update APK to Android's installer (user confirms) | No in-app updates |
| `POST_NOTIFICATIONS` | Guardian turns on the screen-off shortcut | Required visible notification + SOS alert | Shortcut can't be used |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Screen-off shortcut on | Visible foreground service holding a media session to receive volume keys | Shortcut works only while app is open |
| `USE_FULL_SCREEN_INTENT` | Screen-off shortcut on | Let the SOS screen appear over the lock screen | SOS shows as a tappable notification |
| `VIBRATE` | Screen-off shortcut | Two buzzes confirm the sequence was recognised | No buzz |

No background-location, microphone, camera, accessibility, overlay or boot-start permissions are used. The screen-off shortcut records nothing, only counts volume-key presses, and never starts by itself after a restart. Tests send/dial only after the guardian presses a test button (and confirms the SMS dialog); the test-call button opens the dialer only.

## Transparency guarantees
- Normal launcher icon; no hidden mode. SOS always opens a visible screen with a countdown, Cancel, live status.
- Volume keys are passed through to the system; the app never consumes them.
- Readiness is computed from real state (permissions, contacts tested, airplane mode, SIM, battery, location setting). It says "ready" only when the chosen actions can run.
