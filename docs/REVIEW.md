# GOOD — Spec, plan and code review

As of 2026-09-27 (overnight build session). Scope: `docs/SPEC.md` (including its Build plan and milestones) and the M0 scaffold at `a5ab736`.

Severity: **Critical** = can cause a missed alarm or data loss · **High** = a Must requirement can't be met as written · **Medium** = gap or contradiction that would stall the build · **Low** = clarity.

Every finding below was remediated in the spec, the code, or both. The last column says where.

## Reliability (alarm fires every time)

| # | Sev | Finding | Remediation |
|---|---|---|---|
| R1 | Critical | **Reboot overnight misses the alarm.** Alarms registered with `AlarmManager` are cleared on reboot, and `BOOT_COMPLETED` arrives only after the user unlocks the phone. When OxygenOS auto-updates at 3 a.m. and the phone stays locked, nothing is re-registered, so the 06:30 alarm never fires. The spec's "Reboot overnight" test passes only if you unlock the phone. | The ringing path is **direct-boot aware**: the receivers, `WakeService` and `RingActivity` declare `directBootAware`, the schedule snapshot lives in device-protected storage, and `LOCKED_BOOT_COMPLETED` re-registers alarms from it. Room (credential-encrypted) is reconciled after unlock. Same on the watch. Spec: Reliability rule 6. |
| R2 | Critical | **The wake lock expires before auto-silence.** The phone service starts at T−10 and takes a 25-min wake lock, so the lock ends at T+15, before the T+10 escalation completes and before auto-silence at T+20. A profile with a longer light lead is worse. | The hold time is computed from the plan: from the service start until auto-silence, plus 2 min. Spec rule 3 updated. |
| R3 | High | **The ringing screen can close itself at once.** `WakeService` posts the full-screen notification before it publishes `RingUi`, and publishing waits on a watch-reachability check of up to 3 s. `RingActivity` sees `null` and calls `finish()`. | `RingUi` is published before `startForeground`. Both ringing screens close only when the state goes from ringing to idle, never on the initial null. |
| R4 | High | **A dismissed alarm can ring again on the watch.** When the watch dismisses at T−2 and the phone's next `/schedule` still carries that instance as SCHEDULED (it hadn't heard yet), `apply()` re-registers the backup alarm at T. | The watch keeps local terminal states when it merges a snapshot and never re-registers a terminal instance. |
| R5 | High | **The same alarm is re-scheduled after an early dismiss.** Recomputing "next occurrence after now" at T−5 returns T again. | The next instance is computed after `max(now, last instance's T)` (`ScheduleBuilder`, pure, unit-tested). One-shot alarms disarm after they fire. |
| R6 | Medium | **Alarm volume at 0 means a silent alarm.** `AudioTrack.setVolume` is relative to the user's alarm-stream volume. | The CHK mode shows `VOL`. When the sound stage starts with the alarm stream at 0, GOOD raises it to half and logs the change. |
| R7 | Medium | **A late start contradicts itself.** The spec says a passed stage is "skipped, not replayed"; the code replays every passed stage from its gentlest level. Neither is right: after a reboot at T+3 the sound should resume where the ramp would be, not at 5%. | Passed stages start at their current ramp position (`elapsed` passed to `ToneRamp`/`HapticRamp`). An occurrence whose auto-silence time has passed is logged as missed and doesn't ring. Spec Fallbacks updated. |
| R8 | Medium | The `/health` ping at T−15 has no wake-up behind it: the phone registers nothing at T−15. | The ping runs when the phone's first stage starts (T−10), seven minutes before haptics at T−3. Spec updated. |

## Requirements and UX gaps

| # | Sev | Finding | Remediation |
|---|---|---|---|
| U1 | High | **You can't dismiss during the light or haptic stage.** The state diagram allows dismiss from every stage, but both ringing screens show the control only from T. If you're woken at T−6, you can't stop the alarm. | Hold-to-stop is armed from the first stage; the 2-s hold already prevents accidents. |
| U2 | Medium | F1 says "create, edit, delete" alarms, but the UX has four fixed channels AL1–AL4 with no list. | F1 reworded: four channels; arming a channel creates the alarm and disarming deletes it. |
| U3 | Medium | The ALM SET order ends with "sound", which is ambiguous (tone or device?), and the data model has no tone field even though the spec defines a "Classic" sound. | `Alarm.tone` (CHIME / CLASSIC) added. SET order: hour → minute → days → profile → tone → sound target. |
| U4 | Medium | The PRO mode doesn't say which profile it shows or how to switch profiles. | The first PRO row is the profile (P1 GENTLE / P2 QUICK / P3 HEAVY); ▲▼ pick it. The rows are INT 1–4 plus SIL (auto-silence). Hold SET runs the 60-s preview (M4). |
| U5 | Medium | No UI sets the sleep goal (F9), turns the bedtime reminder on or off, or edits a session by hand (Sleep tracking step 6). | In SLP, hold SET 2 s to edit the shown night: BED → WAKE → GOAL → REMIND. |
| U6 | Medium | The watch app's "same four modes" imply editing on the watch, but no Data Layer path carries edits to the phone, which is the source of truth. | On the watch, modes are views, plus two actions: hold on ALM arms or disarms a channel (`/cmd/toggle`), and SET on SLP logs bedtime. |
| U7 | Medium | The tile and complication show "SLP 7:42", but no path carries sleep data from the phone to the watch. | New DataItem `/sleep/summary` (phone → watch). |
| U8 | Medium | Watch sleep source 2 (passive asleep/awake) has no transport to the phone. The data model mentions a watch event queue, but nothing specifies it. | New message `/sleep/signal`. The watch keeps a device-protected outbox (bedtime, signals, dismiss) and flushes it when the phone becomes reachable. |
| U9 | Low | The CHK items omit notifications, and "Background restricted" (Reliability rule 5) isn't mapped to any item. | CHK: ALM · FSI · NTF · BAT (optimisation + background restriction) · VOL · LINK · HC · TST (a real test alarm at +3 min on both devices) · EXP (JSON export). |
| U10 | Low | The JSON export (Data model rules) has no UI entry. | CHK → EXP. |

## Platform and dependencies

| # | Sev | Finding | Remediation |
|---|---|---|---|
| P1 | High | **Background Health Connect reads fail.** Health Connect allows reads only in the foreground unless the app holds `READ_HEALTH_DATA_IN_BACKGROUND`. The planned WorkManager jobs (+30 min, +2 h, 11:00) run in the background. | GOOD requests `READ_HEALTH_DATA_IN_BACKGROUND` when the feature is available. Every sync also runs when the app opens, as a fallback. |
| P2 | Low | Navigation Compose is listed in the build plan, but the UX has "nothing to navigate". | Removed. |
| P3 | Low | Hilt adds a KSP/kapt toolchain for a single-user app with around a dozen singletons. | Replaced by a small hand-written `AppGraph`. Room still uses KSP. A decision is recorded in the spec. |
| P4 | Low | The `/instance/{id}/state` DataItem is specified but not implemented; dismiss is sent only as a message, which is lost if the other device is out of range. | Dismiss is sent as a message (fast) plus the DataItem (catch-up). Both receivers are idempotent. |

## Build plan

The milestone table stands. Because the devices aren't attached overnight, M1–M4 are delivered as code that compiles, installs and passes unit tests. Their gates (7 nights with no misses, dismiss reaching the other device within 2 s, a session on 7 of 7 nights) are on-device checks that start at UAT. Unit tests cover the pure engines: planning, next occurrence, schedule roll-forward, the session builder and sleep metrics.

## Code findings (M0 scaffold)

- `RingActivity`'s header comment says "HOLD STOP appears once the sound stage begins", and a notification comment mentions a "slide". Both are fixed along with U1.
- `AlarmScheduler` request codes use `hashCode()*2+slot`, which can collide. Per-channel codes (`alarmId*2+slot`) are stable and let `FLAG_UPDATE_CURRENT` replace a channel's previous instance.
- `WatchListenerService` answers `/health` with a constant "worn". It now reads the off-body sensor.
- `ScheduleStore` and `WatchScheduleStore` used credential-encrypted SharedPreferences (see R1).

## Found while running the build on emulators (Android 16 phone, Wear OS 5 round)

Both apps were installed on emulators and driven through every mode, a 60-second preview and a real watch alarm registered through the device-protected snapshot. These findings came from that run and are fixed.

| # | Sev | Finding | Remediation |
|---|---|---|---|
| E1 | Critical | **Wear OS doesn't launch full-screen intents.** A real watch alarm fired on time (+0.2 s) and buzzed, but the hold-to-stop screen never appeared: SysUI received the full-screen intent and ignored it, even for an alerting notification. Direct starts from the service, the alarm receiver or an activity-type alarm-clock PendingIntent are all refused as background activity launches on Wear OS 5. | The watch declares `SYSTEM_ALERT_WINDOW`, which exempts GOOD from the background-launch block; the ring service opens `WatchRingActivity` itself. It's granted once at setup with `adb shell appops set com.trepidity.good SYSTEM_ALERT_WINDOW allow` (README), and watch CHK → SCR shows whether it's in place. Without it the alarm still buzzes and sounds, and tapping the notification opens the screen. Spec: Platform constraints. |
| E2 | Critical | **The ring service could crash the app.** `AlarmReceiver` starts the service with `startForegroundService()`; when there was nothing to ring (the occurrence was dismissed on the other device, edited or long past), the service stopped without calling `startForeground()`, and Android killed the app. Reproduced after a reinstall re-delivered a boot broadcast. | Every non-ringing path calls `startForeground()` with a plain notice, then stops (`quietStop`), on the phone and the watch. The ringing path still posts the full-screen notification exactly once, because an update may not re-launch it. |
| E3 | High | Boot re-registration included finished 60-s previews, which is what triggered E2. | Previews use channel −1 and are skipped on boot. They never reach the watch or Room. |
| E4 | High | With GOOD itself open, Android shows an alarm's full-screen intent only as a heads-up, so the preview (and an alarm firing while you're in the app) showed no ringing screen. | The ring service also opens the ringing screen directly, which Android allows while the app is in the foreground. |
| E5 | High | Without notification permission the full-screen alarm can't appear at all, and a fresh install doesn't have it. | GOOD asks on first launch; CHK → NTF still flags it. |
| E6 | Medium | `CaseButton` judged tap vs. hold by when it *processed* the release. On a slow frame, a tap on SET was taken as an abandoned hold and ignored. | It now uses the touch events' own timestamps. |
| E7 | Medium | MODE during an edit both cancelled the edit and moved to the next mode, so one press did two things. | MODE while a field is flashing only leaves set mode, like a real watch. |
| E8 | Low | PRO ▼ went up the list; the ring clock ignored the 12/24-hour setting; "PREVIEW" appeared twice; the STOP button face was a thin dark pill; the panel left two-thirds empty on a tall phone. | All fixed; STOP is now a 96 dp face on a 132 dp target. |

## Found by the independent code review of the finished build

| # | Sev | Finding | Remediation |
|---|---|---|---|
| C1 | Critical | **An overdue occurrence was silently skipped.** When an occurrence stayed `SCHEDULED` past T but inside its silence window (its alarm broadcast never arrived, e.g. dropped by OxygenOS or lost across a reboot), the next `rescheduleAll()` (even just opening the app) rolled it to tomorrow: no late ring, no MISSED entry, and a one-shot stayed armed. Boot re-registration also used a fixed 20-min cutoff (60 s on the watch), shorter than a custom silence window. | `ScheduleBuilder` keeps an overdue, unedited occurrence, so it is re-registered and rings at once with ramps resumed; an edited or disarmed channel is still replaced. Both devices re-register on boot within each profile's own silence window. Two tests; both mutations killed. |

## Found on the real devices (UAT, 2026-09-28)

| # | Sev | Finding | Remediation |
|---|---|---|---|
| F1 | Critical | **The 2R force-stops idle third-party apps, erasing their alarms.** `system_server` (the OnePlus framework) force-stopped GOOD 20–44 s after each time it went idle (`dumpsys activity exit-info`: USER REQUESTED / FORCE STOP). The second test alarm never fired on the watch; the phone's T+5 safety net rang on time. This is the spec's top watch risk, confirmed. | Allowlisting GOOD through adb (deviceidle whitelist, RUN_ANY_IN_BACKGROUND, active standby bucket) stopped the kills; the process survived 4.5 min and more with no new force stop. README setup documents the grants, and watch CHK → PWR shows them. Defence in depth: whenever Play services wakes GOOD on the watch (the phone's T−10 `/health` ping, a sync, a reconnect), it re-registers its stored alarms. |
| F2 | Low | TST registered twice within about 2 s on both runs; the first tap probably gave too little feedback. | Harmless (the second replaces the first); feedback fix pending. |

Verified on hardware: the phone–watch ping (`reachable=true worn=true`); watch dismiss reaching the phone in **546 ms** (F4 gate: ≤ 2 s); the watch ringing screen opening over the watch face with the overlay grant; the phone safety net at T+5 (+0 ms); phone dismiss waking the watch listener.
