# GOOD Alarm — Design & Build Spec

As of 2026-09-30 · Jared · Living copy: the Claude doc "GOOD Alarm — Design & Build Spec" · Revised after the review in [REVIEW.md](REVIEW.md) (finding IDs in brackets)

## Overview & goals

GOOD is a native Android alarm app with a Wear OS companion that wakes you in three stages — phone light, then watch vibration, then sound — and records how long you slept. It is built for one user (a personal Android phone plus a OnePlus Watch 2R on Wear OS 5), sideloaded or published privately, with all data kept on-device.

**Success criteria for v1**

- The alarm fires on time, every time: 0 missed alarms across a 30-night test run, including with the phone in Doze, on battery saver, after a reboot, and with the watch disconnected.
- The wake-up escalates in order: light → watch haptics → sound, with each stage's start delay and duration configurable.
- Every night produces a sleep session (bedtime, wake time, total sleep) visible on the phone, without manual entry.
- Dismiss from either device stops both devices within 2 seconds when they are connected.

## Requirements

V1 covers alarms, the staged wake-up and automatic sleep logging; smart-window waking and sleep staging come after.

| ID | Requirement | Priority |
| --- | --- | --- |
| F1 | Four alarm channels (AL1–AL4): set time, repeat days, wake profile, tone and sound target; arm and disarm. Arming a channel creates the alarm, disarming deletes it [U2] | Must |
| F2 | Staged wake-up: phone light ramp → watch vibration ramp → audible sound on phone and/or watch | Must |
| F3 | Configurable stage offsets and durations per profile (e.g. light at T−10 min, haptics at T−3 min, sound at T) | Must |
| F4 | Dismiss from phone or watch, synced to the other device. No snooze, ever | Must |
| F5 | Watch fires its own stages even when Bluetooth is disconnected | Must |
| F6 | Record a sleep session per night: bedtime, wake time, total duration, awake interruptions | Must |
| F7 | Sleep history: 7-day and 30-day views, average duration, bedtime consistency | Must |
| F8 | Write sleep sessions to Health Connect so other apps can read them | Should |
| F9 | Bedtime reminder based on a sleep goal (e.g. 7.5 h) and the next alarm | Should |
| F10 | Watch tile and complication showing next alarm and last night's sleep | Should |
| F11 | Smart wake window: fire within the last 20 min before the alarm when light sleep is detected | Later |
| F12 | Sleep stages (light/deep/REM) from watch heart-rate data | Later |
| F13 | Skip the next occurrence of a repeating alarm without affecting sleep tracking | Should |
| F14 | I'M UP: the bed button toggles to GOOD MORNING, which records the wake time and closes this morning's alarms | Should |

**Non-functional**

- Reliability: alarms scheduled with the OS alarm-clock API, restored after reboot, time-zone change and app update.
- Privacy: no network permission in v1; data stays in the on-device database, excluded from cloud backup unless you opt in.
- Battery: under 3% overnight drain on the watch and under 2% on the phone from GOOD itself.
- Accessibility: large touch targets on the watch; dismiss works half-asleep without precise taps (press and hold for 2 s: STOP on the phone, anywhere on the watch).
- Out of scope for v1: iOS, other watch brands, cloud sync, sharing.

## Platform constraints

On Android 14+ an alarm fires reliably only through `AlarmManager.setAlarmClock()`, backed by the exact-alarm and full-screen-intent permissions that alarm-clock apps are allowed to hold. The watch is the harder side: weak haptics, a dual-chip design, and sleep data that lives in OHealth.

| Area | Constraint | Design response |
| --- | --- | --- |
| Exact alarms | `SCHEDULE_EXACT_ALARM` is denied by default for new installs on Android 14+; `setAlarmClock()` needs it or `USE_EXACT_ALARM`, which alarm-clock apps may declare and get auto-granted ([source](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)) | Declare `USE_EXACT_ALARM` on phone and watch; still check `canScheduleExactAlarms()` on resume |
| Ringing service | The `systemExempted` foreground-service type is allowed for apps holding `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM`; needs `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` ([source](https://developer.android.com/develop/background-work/services/fgs/service-types)) | Run each wake stage in a `systemExempted` foreground service |
| Lock screen | Android 14 limits `USE_FULL_SCREEN_INTENT` to calling and alarm apps | Check `canUseFullScreenIntent()` at launch; deep-link to the settings page if revoked |
| Target SDK | From Aug 31, 2026, Play requires phone apps to target API 36 and Wear OS apps API 35 ([source](https://developer.android.com/google/play/requirements/target-sdk)) | Phone targetSdk 36, watch targetSdk 35, even if sideloaded |
| Watch OS | Wear OS 5 is based on Android 14; the 2R pairs a Snapdragon W5 Gen 1 with a BES2700 MCU ([specs](https://www.oneplus.com/global/oneplus-watch-2r/specs)) | Watch minSdk 33; keep watch work to short wakes — GOOD runs on the W5, which costs battery |
| Watch hardware | Built-in speaker (used for calls); haptics described as weak; accelerometer, gyroscope, optical HR, SpO2, light sensor; 500 mAh ([review](https://www.smartprix.com/bytes/oneplus-watch-2r-review-an-interesting-wear-os-watch/)) | Watch plays the sound stage by default; haptic patterns use long, full-amplitude pulses |
| Sleep data | OHealth uploads steps, heart rate and sleep to Health Connect ([Sahha](https://sahha.ai/integrations/ohealth/)); Health Connect is built into Android 14 and its `SleepSessionRecord` carries stages ([source](https://developer.android.com/health-and-fitness/health-connect/data-types)) | Health Connect is the primary sleep source |
| Health Connect in background | Reads are foreground-only unless the app holds `READ_HEALTH_DATA_IN_BACKGROUND` | Request it where the feature is available; also sync every time the app opens [P1] |
| Watch ringing screen | Wear OS 5 ignores notification full-screen intents and blocks background activity starts (verified on the emulator, REVIEW E1) | The watch holds `SYSTEM_ALERT_WINDOW`, granted once at setup through adb; the ring service opens the hold-to-stop screen itself. CHK → SCR verifies it |
| Watch sensing | Health Services passive monitoring can report `USER_ACTIVITY_ASLEEP` where the device supports it ([source](https://developer.android.com/training/wearables/health-services/passive)) | Check capabilities on the 2R in the first spike; use only if supported |
| Phone sensing | The Play services Sleep API gives periodic sleep-confidence events and a daily sleep segment ([source](https://developers.google.com/location-context/sleep)); its codelab is marked deprecated | Fallback source only |

## Architecture

GOOD is two native Kotlin apps sharing `:core` libraries: the phone owns the alarm schedule and sleep history, and the watch keeps its own copy of the schedule so its stages fire without the phone.

```mermaid
flowchart TB
  subgraph Phone["Phone app · Android 16 · Kotlin + Compose"]
    UI["UI · Compose<br/>alarms, profiles, sleep"]
    AS["AlarmScheduler<br/>setAlarmClock, reboot restore"]
    WO["WakeOrchestrator<br/>light ramp, sound, escalation"]
    DB["Room database<br/>alarms, instances, sleep"]
    SE["SleepEngine<br/>merges sleep sources"]
    SG["SyncGateway<br/>Data Layer client"]
  end
  OH["OHealth app<br/>syncs watch sleep, HR"] -- writes --> HC["Health Connect<br/>SleepSessionRecord"]
  HC -- read --> SE
  SG <-- sync --> DL["Wearable Data Layer<br/>DataItems: schedule<br/>Messages: dismiss, bedtime"]
  DL <-- "Bluetooth or Wi-Fi" --> SL
  subgraph Watch["Watch app · Wear OS 5 · Compose for Wear OS"]
    WUI["Watch UI · Tile<br/>alarms, bed button"]
    WS["WakeStageService<br/>haptic ramp, watch sound"]
    SL["SyncListener<br/>wakes on sync or message"]
    WSch["Watch scheduler<br/>own setAlarmClock copy"]
    SS["SleepSensing<br/>passive state, bedtime"]
    LS["Local store<br/>schedule snapshot"]
  end
```

The phone is the source of truth; every edit pushes a new schedule snapshot to the watch, and a dismiss on either device is sent as a message and also written as state so a device that was out of range catches up.

| Data Layer path | Type | Direction | Payload |
| --- | --- | --- | --- |
| `/schedule` | DataItem | phone → watch | All enabled alarms with next fire time and wake profile, plus a version number |
| `/instance/{id}/state` | DataItem | both ways | Current state of a firing alarm (ringing, dismissed, silenced, cancelled) with a timestamp |
| `/cmd/dismiss` | Message | both ways | Instance id and `Command.state` (the closing state, default DISMISSED; CANCELLED when the channel was edited or disarmed); the receiver stops its stages within 2 s |
| `/sleep/bedtime` | Message | watch → phone | Time the bed button was pressed |
| `/sleep/wake` | Message | watch → phone | Time I'M UP was pressed on the watch; the phone records a WAKE anchor |
| `/sleep/signal` | Message | watch → phone | Asleep/awake transition from Health Services passive monitoring [U8] |
| `/sleep/summary` | DataItem | phone → watch | Last night's total, bed and wake times, goal, source, plus the latest BED and WAKE anchor times that drive the watch's BED/UP toggle; feeds the tile and complications [U7] |
| `/cmd/toggle` | Message | watch → phone | Arm or disarm a channel from the watch [U6] |
| `/health` | Message | phone → watch | Ping when the phone's first stage starts (T−10), to confirm the watch is reachable and worn; the reply (`/health/reply`) carries the off-body state [R8] |

The watch keeps a small outbox in device-protected storage (bedtime, sleep signals, dismiss) and flushes it whenever the phone becomes reachable, so nothing it records is lost while the phone is out of range [U8].

## Gentle wake-up sequence

Each alarm runs a wake profile of four timed stages relative to the alarm time T; both devices compute the same stage times from T, so neither waits on the other to start.

```mermaid
stateDiagram-v2
  direction LR
  Light: 1 · Light — phone sunrise ramp, T−10 → T
  Haptics: 2 · Haptics — watch pulses ramp, T−3 → T
  Sound: 3 · Sound — watch if worn, fading in from T
  Escalate: 4 · Escalate — watch full volume from T+5
  [*] --> Light
  Light --> Haptics
  Haptics --> Sound
  Sound --> Escalate
  Light --> Dismissed: dismiss
  Haptics --> Dismissed: dismiss
  Sound --> Dismissed: dismiss
  Escalate --> Dismissed: dismiss
  Dismissed --> [*]
```

There is no snooze: dismiss from any stage — including the light and haptic stages, where the 2-s hold is armed from the start [U1] — ends the alarm everywhere within 2 s and stamps the wake time on the sleep session; auto-silence at T+20 is the only other exit.

**Default profile ("Gentle")** — every value is editable per profile.

| Stage | Device | Starts | Ramp | Implementation |
| --- | --- | --- | --- | --- |
| 1 Light | Phone | T−10 min | Brightness 1% → 100% over 10 min, warm red → amber → white | Full-screen `RingActivity` with `setShowWhenLocked` and `setTurnScreenOn`; animate `WindowManager.LayoutParams.screenBrightness`; optional torch at low strength (`turnOnTorchWithStrengthLevel`) |
| 2 Haptics | Watch | T−3 min | One 400 ms pulse every 20 s, rising to 3 × 800 ms every 5 s | `VibrationEffect.createWaveform` at full amplitude; the 2R's motor is weak, so length does the work rather than amplitude |
| 3 Sound | Watch when worn and reachable (default), otherwise phone. Also selectable: phone, watch, or both | T | Starts soft (about 5%) and keeps rising every 10 s until dismissed; full volume by T+5 min | `AudioTrack`/`MediaPlayer` on `USAGE_ALARM` so Do Not Disturb lets it through; on the watch, route to the built-in speaker and step the player volume; M0 checks how quiet the 2R's speaker can go |
| 4 Escalate | Both | T+5 min | Watch at 100% with continuous haptics; phone sound joins at T+10 min as a safety net | Same services, max settings |
| Auto-silence | Both | T+20 min | — | Stop, log as unanswered, post a notification |

**Fallbacks**

- **Watch unreachable or off-wrist.** The phone pings `/health` when its first stage starts (T−10 min); the watch answers with its off-body sensor state. No answer or off-wrist → the phone vibrates in stage 2 and plays the sound in stage 3.
- **Watch never got the latest schedule.** The watch fires from its last snapshot; the schedule `version` lets the phone detect and log the mismatch.
- **Phone in use at T−10.** The system shows a heads-up notification instead of the full screen; GOOD skips the light ramp because the screen is already on.
- **Phone rebooted overnight.** A `LOCKED_BOOT_COMPLETED` receiver re-registers alarms from the device-protected snapshot before anyone unlocks the phone [R1]. If the ring service starts late, passed stages resume at their current ramp position (the sound doesn't restart at 5%); an occurrence whose auto-silence time has already passed is logged as missed and does not ring [R7].
- **Alarm volume at zero.** If the alarm stream is at 0 when the sound stage starts, GOOD raises it to half and logs it; CHK shows VOL [R6].
- **Snooze.** None, by design. No screen, notification or button on either device offers one.

## Sleep tracking

GOOD records one sleep session per night by merging up to four sources, preferring the watch's own tracker (OHealth, read through Health Connect) and falling back to GOOD's own signals.

| Priority | Source | Gives | Available |
| --- | --- | --- | --- |
| 1 | Health Connect sessions written by OHealth | Start, end, and stages if OHealth includes them | After OHealth syncs, usually within an hour of waking |
| 2 | Watch passive state (Health Services `USER_ACTIVITY_ASLEEP`) | Asleep and awake transitions | Only if the 2R reports the capability — verified in milestone M0 |
| 3 | Phone Sleep API (Play services) | Sleep confidence every few minutes, a daily sleep segment | Always, if `ACTIVITY_RECOGNITION` is granted |
| 4 | Anchors | Bed-button press on watch or phone; alarm dismiss time | Always |

**How a session is built**

1. Each night window runs 18:00 to 14:00 the next day and belongs to the wake date.
2. On dismiss or I'M UP, GOOD writes a provisional session: start = bed-button time (or first asleep signal), end = dismiss or I'M UP time.
2a. A night whose alarm was skipped and has no I'M UP ends at the first phone unlock or watch "awake" after the skipped time, if under 24 h (#1). The unlock times need Usage access (CHK → USE); without it only watch "awake" samples count. The inferred time is used for that build only, so a later Health Connect session or an I'M UP press still wins.
3. A WorkManager job runs at dismiss +30 min, +2 h and at 11:00, and a daily pass at 11:00 also rebuilds the last two nights (enqueued at app start and after every wake anchor). It reads Health Connect sleep sessions overlapping the window; one from another app replaces the provisional values and is tagged with its source.
4. With no Health Connect session, GOOD derives one from sources 2–3: sleep onset = start of the first 20-min asleep run; wake = last asleep sample before dismiss; an awakening = an awake run of 5 min or more.
5. Only sessions GOOD built itself are written back to Health Connect, so OHealth's data is never duplicated.
6. You can edit start and end by hand; edited sessions are marked and never overwritten by later syncs.

**Metrics shown**

- Time in bed and total sleep, per night and as 7- and 30-day averages
- Sleep onset latency (bed button → asleep) and number of awakenings
- Sleep debt against your goal over the last 7 nights
- Bedtime consistency: spread of bedtimes over 14 nights, in minutes
- Wake behaviour: on the SLP lap for a morning, the stage you dismissed in and the minutes after the first stage (`WOKE SND +6`); `UP EARLY` when I'M UP closed it before any stage; `SKIPPED`; `NO ANSWER` when auto-silence ended it. An occurrence cancelled by an edit or disarm has no line (#7)

Sleep stages are displayed only when OHealth provides them; GOOD does not compute its own stages in v1 (F12).

## UX design

GOOD doesn't look like a stock alarm app with lists, toggles and a floating + button. It looks and works like a '90s digital sports watch, a nod to Timex's Ironman-era LCD watches: one LCD panel, a LIGHT button, and four case buttons that change meaning by mode.

```
 ┌──────────────────────────────┐
 │ ┌──────────────────────────┐ │   Modes, not screens: ALM · SLP · PRO · CHK
 │ │ [ALM]  SLP   PRO   CHK   │ │   (MODE button, or swipe sideways on the LCD)
 │ │                          │ │
 │ │   6:30              AL1  │ │   Seven-segment digits; unlit segments faintly visible
 │ │  M T W T F S S           │ │   Alarm channels AL1–AL4 (four fixed alarms, no list)
 │ │  IN 7:20 · GENTLE        │ │
 │ │  🔔  ∞  ☾                │ │   Glyphs: armed · watch linked · sleep tracking
 │ └──────────────────────────┘ │
 │         (   LIGHT   )        │   Teal night glow for 3 s
 │   (  SET  )     (   ▲   )    │   SET: field flashes; ▲▼ change it (hold = fast)
 │   (  MODE )     ( ▼/STOP )   │   While ringing: hold STOP 2 s to dismiss
 └──────────────────────────────┘
```

Every screen is the same instrument in a different mode, so there is nothing to navigate, only MODE to press.

**Modes** (MODE button, or swipe sideways on the LCD)

| Mode | LCD shows | ▲ / ▼ | SET |
| --- | --- | --- | --- |
| ALM · Alarm | Next alarm in big digits, channel (AL1–AL4), lit weekday segments, "IN 7:20 · GENTLE" | Switch channel AL1 → AL4; hold ▼ 2 s: skip or unskip the next occurrence (banner `SKIPPED` / `UNSKIP`) | Edit: hour flashes → minute → days → profile → tone (CHIME / CLASSIC) → sound target (AUTO / PHONE / WATCH / BOTH) [U3]; hold SET 2 s to arm or disarm |
| SLP · Sleep | Last night's total ("7:42") as a chrono readout, bed → wake, a 7-night LCD bar graph, OH glyph when the data came from OHealth; the wake line under BED / UP (`WOKE SND +6`, `UP EARLY`, `SKIPPED`, `NO ANSWER`); a small line saying what SET logs now (`SET GOOD NIGHT` / `HOLD SET GOOD MORNING`; the watch shows `BED` / `UP` beside `SLP`); 7- and 30-day averages, debt and bedtime spread on a second line | Recall LAP 01 → LAP 30 (one lap per night) | Tap SET logs bedtime now (GOOD NIGHT). After it, GOOD MORNING (I'M UP: the wake time, and it closes this morning's alarms on both devices) needs SET held 2 s on the phone and the watch, so a stray tap can't cancel the morning's alarms; a tap then only shows `HOLD SET`. The watch's SLP flashes MORNING (GOOD MORNING doesn't fit inside the ring ticks). In the BED state, hold SET 2 s to edit the shown night: BED → WAKE → GOAL → REMIND on/off [U5] |
| PRO · Profile | The wake profile as an interval timer: P1 GENTLE, INT 1 LIGHT 10:00, INT 2 BUZZ 3:00, INT 3 TONE 5:00, INT 4 FULL +5:00, SIL 20 | Step through rows (first row picks the profile P1–P3) [U4] | Edit the flashing row; hold SET 2 s for the 60-s preview |
| CHK · Check | Self-test like a watch's segment test: ALM, FSI, NTF, BAT, VOL, LINK, HC, USE each show a check or blink; then TST and EXP [U9, U10] | Step through items | Open the fix for the blinking item; on TST, set a real test alarm at +3 min on both devices; on EXP, export all data as JSON |

**Ringing**

- Phone: the LCD panel is the sunrise. Its backlight warms from deep red through amber to white over the light stage while the digits stay dark, like a lit LCD.
- Dismiss = press and hold STOP (lower right) for 2 s. A segment bar fills across the LCD with a rising haptic tick; releasing early does nothing. There is no snooze control on either device.
- Watch: the 2R's round screen shows the time in segments. To dismiss, press and hold anywhere for 2 s while 60 segments fill around the edge, like a seconds track. The system swipe-to-close is disabled.

**Watch surfaces**

| Surface | Contents |
| --- | --- |
| Tile | LCD strip: `AL1 6:30` and `SLP 7:42`, with a BED/UP button. BED logs a bedtime (GOOD NIGHT); UP opens the watch app in SLP, where GOOD MORNING is the 2 s hold (a tile has no hold) |
| Complications | Next alarm in segment digits (short text); last night's sleep against goal (ranged value) |
| App | The same four modes as views of the phone's data. Tap the top half for ▲ and the bottom half for ▼; swipe sideways for MODE; long-press for SET. Actions: on ALM, long-press arms or disarms the channel (sent to the phone); on SLP, long-press (2 s) logs bedtime or, after it, GOOD MORNING (I'M UP); on CHK, long-press runs the item's test [U6] |

**Look and feel**

- Two panel states: *Day LCD* (grey-green reflective panel, near-black segments) and *Night glow* (black case, teal-lit panel when LIGHT is pressed). No white UI ever appears at night.
- Type: seven-segment digits and 14-segment letters drawn in Compose `Canvas`, so no font licence is needed. Button labels in small caps.
- Sound option "Classic": the four-beep digital-watch alarm pattern. Like the chime, it starts at about 5% and keeps rising.
- Haptics: a crisp tick per button press, a double tick on MODE, rising ticks while STOP is held.
- Trademark note: the design only nods to Timex. The app never uses Timex names or logos, or "Indiglo" or "Ironman"; the night light is simply called GLOW.

## Data model & storage

The phone keeps everything in one Room database; the watch keeps only a snapshot of the schedule plus a small queue of events to send.

| Entity | Key fields | Notes |
| --- | --- | --- |
| `Alarm` | id (channel 1–4), hour, minute, repeatDays (bitmask), label, enabled, profileId, tone (CHIME / CLASSIC), soundTarget (AUTO / PHONE / WATCH / BOTH; AUTO = watch when worn and reachable, else phone), skipNextDate | Source of truth for the schedule |
| `WakeProfile` | id, name, stages (JSON list of `Stage`), autoSilenceMinutes (no snooze fields) | Presets seeded on first launch |
| `Stage` | type (LIGHT / HAPTIC / SOUND / ESCALATE), device, offsetSec (relative to T), rampSec, params | Embedded in `WakeProfile` |
| `AlarmInstance` | id, alarmId, scheduledAt (UTC), state (SCHEDULED / FIRING / DISMISSED / SILENCED / SKIPPED / CANCELLED), currentStage, firstStageAt, dismissedAtStage, dismissedAt, dismissedOn (PHONE / WATCH) | One row per occurrence; drives the state machine and the wake metrics. SKIPPED is written by SKIP only; I'M UP closes occurrences as DISMISSED before ringing; CANCELLED means the channel was edited or disarmed |
| `SleepSession` | id, wakeDate, start, end, source (HEALTH_CONNECT / WATCH / PHONE / ANCHORS), sourcePackage, edited, healthConnectId | One per night |
| `SleepSegment` | sessionId, start, end, kind (ASLEEP / AWAKE / LIGHT / DEEP / REM) | Stages only when the source provides them |
| `SleepSignal` | timestamp, source, confidence, motion, light | Raw phone and watch samples; pruned after 14 days |
| `EventLog` | timestamp, device, type, detail | Diagnostics; pruned after 30 days |

**Rules**

- All times stored as UTC instants; alarm times as local wall-clock hour and minute, recomputed on time-zone change and daylight-saving shifts.
- The `/schedule` DataItem is a serialized list of the next `AlarmInstance` per enabled alarm, with its `WakeProfile` inlined and a monotonically increasing `version`.
- The database is excluded from Android auto-backup by default (`dataExtractionRules`); an opt-in export writes a JSON file you choose.

## Permissions, reliability & battery

| Permission | Device | Why | How granted |
| --- | --- | --- | --- |
| `USE_EXACT_ALARM` | Both | `setAlarmClock()` for stage start times | Install time (alarm-clock apps) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` | Both | Run the wake stages as a foreground service | Install time |
| `USE_FULL_SCREEN_INTENT` | Both | Ringing screen over the lock screen | Default for alarm apps; verified at launch |
| `POST_NOTIFICATIONS` | Both | Alarm, bedtime and unanswered-alarm notifications | Runtime prompt |
| `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `VIBRATE` | Both | Reschedule after reboot; keep CPU awake during stages; haptics | Install time |
| `ACTIVITY_RECOGNITION` | Both | Phone Sleep API; watch passive asleep state | Runtime prompt |
| `health.READ_SLEEP`, `health.WRITE_SLEEP` | Phone | Read OHealth sessions, write GOOD's own | Health Connect consent screen |
| `health.READ_HEALTH_DATA_IN_BACKGROUND` | Phone | Sleep sync jobs read Health Connect after dismiss and at 11:00 [P1] | Health Connect consent screen |
| `health.READ_HEALTH_DATA_HISTORY` | Phone | One-time import of sleep older than 30 days | Health Connect consent screen (optional) |
| `PACKAGE_USAGE_STATS` | Phone | Unlock times for a skipped night's wake | Settings → Usage access (CHK → USE) |

**Reliability rules**

1. Phone: one `setAlarmClock()` at the first stage (T−10) plus a backup at T. The status bar therefore shows the first-stage time; that trade-off is accepted because `setAlarmClock()` is the only call exempt from Doze without rate limits.
2. Watch: `setAlarmClock()` at its first stage (T−3) plus a backup at T, from its own snapshot.
3. The stage timeline runs inside the foreground service with a partial wake lock, released on dismiss or silence; its hard cap is computed from the plan (service start → auto-silence + 2 min), never a fixed 25 min [R2].
4. Every scheduling path (boot, app update, time change, time-zone change, schedule sync) goes through one idempotent `rescheduleAll()`.
5. The Reliability check screen reads `canScheduleExactAlarms()`, `canUseFullScreenIntent()`, `isIgnoringBatteryOptimizations()` and `isBackgroundRestricted()`, and links to the fix for each.
6. Direct boot: the alarm receivers, the ring services and the ringing screens are `directBootAware`, and the schedule snapshot they need lives in device-protected storage, so a reboot with the phone still locked (e.g. an overnight update) re-registers and rings the alarm [R1].
7. The next occurrence is computed after max(now, the previous occurrence's T), so dismissing early never re-arms the same morning; a one-shot alarm disarms after it fires [R5].

**Battery budget (overnight, GOOD only):** watch under 3% (no continuous sensor listeners; wakes limited to syncs, the T−10 ping and the stage service (T−3 → auto-silence)); phone under 2%.

## Build plan

Single Gradle project in Kotlin with two app modules that share one package name and signing key — the Wearable Data Layer only connects apps that match on both.

| Concern | Choice |
| --- | --- |
| Language and build | Kotlin 2.x, Gradle version catalog; phone minSdk 34 / targetSdk 36 (OnePlus 12 on OxygenOS 16, Android 16), watch minSdk 33 / targetSdk 35 |
| Phone UI | Jetpack Compose; the LCD instrument is drawn in `Canvas` (no Navigation: one screen, four modes) [P2] |
| Watch UI | Compose for Wear OS, Horologist, Tiles (ProtoLayout), complication data source |
| State and DI | Coroutines and Flow; a hand-written `AppGraph` instead of Hilt [P3] |
| Storage | Room (phone), DataStore (both), kotlinx.serialization for Data Layer payloads |
| Scheduling | `AlarmManager.setAlarmClock()`; WorkManager only for sleep sync jobs |
| Sync | `play-services-wearable`: DataClient, MessageClient, NodeClient |
| Health | `androidx.health.connect:connect-client` (phone), `androidx.health:health-services-client` (watch), `play-services-location` Sleep API (phone) |
| Testing | JUnit, Turbine, Robolectric, Compose UI tests |

**Milestones** (part-time pace)

| Milestone | Weeks | Scope | Gate |
| --- | --- | --- | --- |
| M0 Spike | 1 | Prove the watch can ring, buzz and play sound; check Health Connect receives OHealth sleep | Go/no-go: watch rings with Bluetooth off; pick v1 sleep sources |
| M1 Phone alarm core | 2–3 | Alarms, scheduler, ring service, light ramp, sound, Reliability check | 7 nights phone-only, zero misses (incl. forced Doze and a reboot) |
| M2 Watch companion | 4–5 | Data Layer sync, watch scheduler, haptics, dismiss sync, fallbacks | Dismiss reaches the other device in 2 s; watch fires with phone out of range |
| M3 Sleep tracking | 6–7 | Health Connect read/write, Sleep API, bed button, session builder, Sleep screen | A session appears for 7 of 7 nights |
| M4 Polish | 8 | Tile, complications, profile editor, bedtime reminder, 60 s preview | All Must and Should requirements demoed |
| M5 Burn-in | 9–12 | 30 nights of daily use, EventLog reviewed weekly | v1.0: zero missed alarms in 30 nights |

## Testing plan

The stage engine and sleep-session builder are pure Kotlin and tested with a fake clock; everything that touches the OS is tested on the real phone and the 2R.

| Scenario | How | Pass |
| --- | --- | --- |
| Deep Doze | `adb shell dumpsys deviceidle force-idle` on each device, alarm 5 min out | Every stage starts within 5 s of plan |
| Reboot overnight | Reboot phone and watch at T−30 | Alarm fires on both |
| App update | `adb install -r` with an alarm pending | Alarm still fires |
| Phone out of range | Phone Bluetooth off at T−20 | Watch runs its stages from its snapshot |
| Watch off-wrist | Remove watch before T−15 | Phone vibrates in stage 2 and sounds in stage 3 |
| Cross-device dismiss | Dismiss on watch, then on phone, on separate nights | Other device stops within 2 s |
| Do Not Disturb and battery saver | Both on, on both devices | Light, haptics and sound all run |
| Time-zone change | Change zone with an alarm set | Next fire time follows local wall-clock time |
| Sleep session | Normal night with OHealth tracking | Session shown by 11:00 with the OHealth source badge |

## Risks & decisions

| Risk | Impact | Mitigation |
| --- | --- | --- |
| The 2R's dual-chip power management delays or blocks third-party alarms and full-screen screens on the watch | Stage 2 fails silently | M0 spike; phone always vibrates as backup when the watch misses its `/health` ping |
| Watch haptics too weak to wake you | Stage 2 ineffective | Longer, denser patterns; option to start watch sound at low volume in stage 2 |
| Watch speaker can't play quietly enough for a gentle start | Sound stage not gentle | M0 measures the lowest usable gain; fall back to phone sound for the first minute |
| OHealth writes sleep late, only as a duration, or not at all | Sleep history gaps | Sources 2–4 build the session; Health Connect data replaces it when it arrives |
| Play services Sleep API is retired | Phone-side fallback lost | Anchors plus watch passive state still produce a session |
| Health Services doesn't report `USER_ACTIVITY_ASLEEP` on the 2R | No watch-side sleep signal; smart wake (F11) not possible | Rely on Health Connect and anchors; revisit F11 |
| OxygenOS battery management on the OnePlus 12 stops the ring service | Missed alarm | `setAlarmClock()` plus backup alarm; battery use set to Unrestricted; Reliability check flags restrictions |

**Decisions**

- **Phone:** OnePlus 12 on OxygenOS 16 (Android 16), so the phone app uses minSdk 34.
- **Distribution:** installed from Android Studio onto both devices; no Play listing for now.
- **Sleep source:** keep the OHealth integration (via Health Connect) as the primary sleep source. Replacing it with GOOD's own tracking, which smart wake (F11) and sleep stages (F12) would need, is a possible future phase, not v1.
- **Snooze:** none, ever. An alarm ends only by dismiss or auto-silence.
- **DI:** no Hilt; a dozen singletons don't justify a second annotation-processing toolchain (Room already uses KSP).
- **Verification split:** M1–M4 code is built and unit-tested off-device; each milestone's gate is an on-device check run at UAT and in M5.

## Sources

- [Android 14 exact-alarm changes](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Google Play target API level requirements](https://developer.android.com/google/play/requirements/target-sdk)
- [Health Connect data types](https://developer.android.com/health-and-fitness/health-connect/data-types)
- [Health Services passive monitoring](https://developer.android.com/training/wearables/health-services/passive)
- [Sleep API](https://developers.google.com/location-context/sleep)
- [OnePlus Watch 2R specs](https://www.oneplus.com/global/oneplus-watch-2r/specs)
- [OnePlus Watch 2R review, Smartprix](https://www.smartprix.com/bytes/oneplus-watch-2r-review-an-interesting-wear-os-watch/)
- [OHealth integration, Sahha](https://sahha.ai/integrations/ohealth/)
