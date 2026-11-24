# Test plan

Legend: **[AUTO]** = automated (`gradlew testDebugUnitTest`, 11 tests pass). **[DEVICE]** = needs a real phone with a SIM (emulators can't judge hardware keys, real SMS/GPS/lock-screen behaviour). Record phone model + Android version for each run. Use a spare contact who agrees to receive tests; SMS charges may apply.

## 1. Trigger accuracy
| # | Steps | Expected |
|---|---|---|
| 1.1 [AUTO] | 5 Down presses 400 ms apart, window 3 s | Fires on the 5th only |
| 1.2 [AUTO] | 5 presses 1 s apart | No fire (spans 4 s) |
| 1.3 [AUTO] | 4 presses; wrong key in sequence; Power key code | No fire |
| 1.4 [AUTO] | Alternating pattern right/wrong order | Fires only when correct |
| 1.5 [AUTO] | After firing, 1 more press | Progress is 1 of N (no double fire) |
| 1.6 [DEVICE] | Trigger test screen: 20 attempts of the real sequence at normal speed, then 20 slightly-too-slow attempts | Target >= 19/20 recognised, 0/20 slow ones. Note any phone that misses presses. |
| 1.7 [DEVICE] | Hold Volume Down for 5 s | Counts as one press (repeats ignored); no trigger |
| 1.8 [DEVICE] | Volume keys with Bluetooth headset / wired remote | Record behaviour (may be routed elsewhere) |
| 1.9 [DEVICE] | Screen-off shortcut mode OFF: press the sequence with screen off, on lock screen, in another app | **Must NOT trigger**; child screen says it only works while the app is open |
| 1.10 [DEVICE] | Press Power 5x on the child screen | Nothing happens; docs say unsupported |
| 1.11 [DEVICE] | Volume still changes normally (media/ring volume UI appears) while using the app | Yes: keys are not consumed |
| 1.12 [DEVICE] | Rotate screen, font scale 200%, dark mode on test screen | No crash; progress still correct |

## 2. Accidental activation
| # | Steps | Expected |
|---|---|---|
| 2.1 [DEVICE] | Phone in a pocket/bag, app open with screen on for 10 min (keep-screen-on enabled) | No SOS from pocket presses; note rate of false triggers |
| 2.2 [DEVICE] | Trigger with 5 s cancel time, tap **CANCEL - I'M OK** | Nothing sent, no call, alarm (if any) silent, status "Nothing was sent" |
| 2.3 [DEVICE] | Trigger, press Back during countdown | Same as cancel |
| 2.4 [DEVICE] | Trigger, tap **SEND HELP NOW** | Skips countdown, runs immediately |
| 2.5 [DEVICE] | Cancel at "Getting help..." after SMS already sent | Shows "message already sent"; **TELL CONTACTS I'M OK** sends one follow-up only on tap |
| 2.6 [DEVICE] | Cancel time 0 | Runs immediately, no countdown |
| 2.7 [DEVICE] | Tap the on-screen SOS button once | Opens countdown (not instant send) when cancel time > 0 |
| 2.8 [DEVICE] | SOS disabled in guardian settings, press SOS button/sequence | Sequence ignored; button shows "SOS is turned off" + guidance; nothing sent |

## 3. Permissions
| # | Steps | Expected |
|---|---|---|
| 3.1 [DEVICE] | Fresh install: open app | No permission prompts at launch |
| 3.2 [DEVICE] | Turn on SMS action | Explanation shown first; prompt only when "Allow SMS" pressed |
| 3.3 [DEVICE] | Deny SMS once, twice | Message + "Open phone settings"; Setup check says SMS off (blocking); SOS run reports "SMS permission is off" and still does call/alarm |
| 3.4 [DEVICE] | Deny Location | SMS still sent saying "Location unavailable." |
| 3.5 [DEVICE] | Deny Phone permission | Dialer opens prefilled with warning |
| 3.6 [DEVICE] | Revoke a permission in system settings while app is in background, return | Setup check/child status update on resume |
| 3.7 [DEVICE] | Test message / test call | Nothing is sent until the test button is pressed and (for SMS) confirmed |
| 3.8 [DEVICE] | PIN: wrong PIN x5 | Lockout message; correct PIN refused during lockout; settings not reachable without PIN; relocks after 10 min or via "Lock settings" |

## 4. Location failure
| # | Steps | Expected |
|---|---|---|
| 4.1 [DEVICE] | Outdoors with GPS | SMS has maps link + accuracy |
| 4.2 [DEVICE] | Indoors/airplane with GPS off but recent cached fix | Message labelled "Last known location (N min old)" (<= 15 min) |
| 4.3 [DEVICE] | Phone Location setting off | Warning in Setup check; during SOS log says setting is off; SMS says "Location unavailable." and is still sent |
| 4.4 [DEVICE] | No fix possible | Waits <= 8 s then sends without location |
| 4.5 [DEVICE] | Check for location icon / background use | Location indicator only during SOS; none otherwise |

## 5. Delivery failure
| # | Steps | Expected |
|---|---|---|
| 5.1 [DEVICE] | Airplane mode on, trigger SOS | Warning shown up front; SMS failure "airplane mode/radio off"; guidance + **TRY AGAIN**; results never say "sent" |
| 5.2 [DEVICE] | No SIM / SIM locked | "SIM card isn't ready" warning; failure reported |
| 5.3 [DEVICE] | Weak signal | Sent only after network confirmation or "no answer after 30 seconds" error |
| 5.4 [DEVICE] | Two contacts, one invalid number | One OK, one error line naming the contact; overall "partly sent" |
| 5.5 [DEVICE] | Zero credit/blocked sender | Failure reported (carrier-dependent: some carriers still report "sent") |
| 5.6 [DEVICE] | Low battery (<= 15%, not charging) | Warning on child screen and during SOS; SOS still runs |
| 5.7 [DEVICE] | No dialer app / call blocked | "Couldn't start the call" error + guidance |
| 5.8 [DEVICE] | Message length > 160 chars (long message + URL) | Multipart; success only when all parts report sent |
| 5.9 [DEVICE] | Alarm: run with alarm on, then **STOP ALARM** | Loud alarm stream; stops immediately; alarm volume restored |

## 6. Other devices/OS
- [DEVICE] Run section 1.6, 2.2, 4.1, 5.1 on: one Android 8-9, one 12-13, one 14-15 phone, and at least two OEMs (e.g. Samsung, Xiaomi/Oppo). Record any OEM that restricts SMS, shows extra "send SMS?" dialogs, or kills the app.
- [DEVICE] Quick Settings tile: add, tap unlocked (opens SOS countdown); tap locked (expect unlock prompt).
- [DEVICE] Lock-screen display: start SOS from the on-screen button, lock phone during countdown; the SOS screen should be visible over the lock screen (Android 8.1+).
- [DEVICE] Dual-SIM: confirm which SIM sends SMS (default SMS SIM).

## 7. Accessibility
- [DEVICE] TalkBack on: complete setup using swipe navigation only. Check: every control has a label; toggles announce state; cancel button reachable in <= 3 swipes at SOS screen; countdown number and status lines are announced (live regions).
- [DEVICE] Font size and display size at maximum: no clipped text, buttons still reachable by scrolling.
- [DEVICE] Switch Access / keyboard Tab navigation reaches all controls.
- [DEVICE] Grayscale / colour inversion: status still readable (words, not colour only). Contrast: palette designed for >= 7:1 body text; verify with an accessibility scanner.

## 8. Screen-off shortcut (optional mode) - all [DEVICE]
| # | Steps | Expected |
|---|---|---|
| 8.1 | Turn mode on without notification permission (Android 13+) | Prompt with reason; warning in Setup check until allowed |
| 8.2 | Mode on: persistent notification with "Turn off shortcut"; tap it | Notification always present while on; Turn off stops service and clears status |
| 8.3 | Start 60-s test, screen off, press sequence | Two buzzes; on return: "Tested". Repeat on 3 phone models; record failures |
| 8.4 | Test with screen ON | Recognised but NOT marked tested (screen-off required) |
| 8.5 | Mode on but never tested | Child screen says "ON but NOT tested yet"; Setup check warns |
| 8.6 | Real trigger, screen off, locked phone | Buzz, SOS screen over lock screen with countdown and Cancel; on Android 14+ without full-screen permission, a heads-up notification to tap |
| 8.7 | Volume changes while mode on (screen on and off) | Volume still changes and shows the system slider |
| 8.8 | Hold Volume Down 5 s | Counts as one press, no trigger |
| 8.9 | Reboot phone | Service not running; status says STOPPED; opening the app restarts it |
| 8.10 | Swipe app from recents, battery saver on, wait 30 min, retest | Record whether the OEM killed it; Unrestricted battery guidance works |
| 8.11 | During a phone call / media playing / other app holding the media session | Record behaviour (key routing may go elsewhere) |
| 8.12 | Pocket test with mode on and screen off, 30 min | Record accidental triggers; the cancel countdown still protects |

## 9. Updates
| # | Steps | Expected |
|---|---|---|
| 9.1 [AUTO] | Version comparison unit tests | Pass |
| 9.2 [DEVICE] | Unconfigured build | "No GitHub repository configured" |
| 9.3 [DEVICE] | Publish v1.0.1 (APK + .sha256) from a signed build; check in app | "Version 1.0.1 available"; download shows progress; Install opens Android's confirm screen; app updates, settings kept |
| 9.4 [DEVICE] | Release without checksum | Rejected: no SHA-256 |
| 9.5 [DEVICE] | APK that doesn't match its checksum | Deleted: failed SHA-256 check |
| 9.6 [DEVICE] | APK signed with a different key (e.g. debug) | Blocked: not signed by the same key |
| 9.7 [DEVICE] | Airplane mode | "Couldn't reach GitHub" |
| 9.8 [DEVICE] | Auto-check option on | Banner on Guardian settings only; nothing downloads by itself |
