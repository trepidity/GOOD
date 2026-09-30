# Skip next and I'M UP — Design

As of 2026-09-30 (rev. 2, after issue review) · Jared · Extends [SPEC.md](../../SPEC.md) (F1, F4, F6) · Tracking: [#9](https://github.com/trepidity/GOOD/issues/9), labels `feature: skip & I'M UP`, `decision: open`, `known-issue`

## Goal

Two controls for mornings that don't go to plan:

- **SKIP**: no alarm on the next occurrence of a repeating channel, **but you're still asleep**. Sleep is tracked as usual, so sleeping in counts.
- **I'M UP**: you're awake and staying up. It records your wake time and cancels any alarm still due this morning.

They are separate because they mean opposite things for sleep: SKIP says "keep counting," I'M UP says "stop counting now."

The issue review added four items to this feature:

| Issue | Decision | Where in this spec |
| --- | --- | --- |
| [#1](https://github.com/trepidity/GOOD/issues/1) | Infer the wake time of a skipped night from the first phone unlock or watch "awake" after the skipped alarm, if the night is under 24 h | Inferred wake |
| [#5](https://github.com/trepidity/GOOD/issues/5) | Close an occurrence replaced by an edit instead of leaving it open | Closing occurrences |
| [#6](https://github.com/trepidity/GOOD/issues/6) | Disarming or editing a channel stops its running wake-up on both devices | Closing occurrences |
| [#7](https://github.com/trepidity/GOOD/issues/7) | Build the wake-behaviour metric; early dismisses (I'M UP) are shown apart | Wake behaviour |

Closed as won't do: #2 (automatic awake detection; I'M UP stays manual) and #4 (multiple skip dates). Still open: #3 (SKIP from the watch) and #8 (verify passive asleep state on the 2R).

**Success criteria**

- A skipped occurrence never rings on either device, and undoing the skip before the day restores it on both.
- I'M UP pressed on the watch with the phone out of range stops the watch's alarm immediately. When the phone comes back, the phone's alarm is cancelled too if it hasn't rung yet.
- An early wake is recorded at the I'M UP time, not the alarm time, when no better source exists.
- A skipped night still gets a session by 11:00 whenever Health Connect or sleep samples have data. With neither, it gets one if there is a bedtime and an inferred wake.
- Turning a channel OFF during its sunrise stops the sunrise and any watch buzz within 2 s when connected.
- The SLP lap for a morning shows how you woke: the stage you dismissed in and minutes after the first stage, or `UP EARLY`, `SKIPPED`, `NO ANSWER`.

## Behaviour

### SKIP (phone, ALM mode)

| Input | Result |
| --- | --- |
| Hold **▼** 2 s on a repeating channel whose next occurrence is `SCHEDULED` | `skipNextDate` = that occurrence's local date. Banner `SKIPPED`. Status line: `SKIP WED · NEXT THU 6:30 · GENTLE` |
| Hold **▼** 2 s while a skip is pending (`skipNextDate` ≥ today) | Skip cleared. Banner `UNSKIP`. The occurrence returns |
| Hold **▼** 2 s on a one-shot channel | No change. Banner `ONCE — USE OFF` |
| Hold **▼** 2 s while the occurrence is ringing | No change. The ring screen's STOP is the control |

- Outside edit mode, ▼ no longer repeats when held, so the 2-s hold doesn't cycle channels. ▲ is unchanged. In edit mode ▼ keeps its repeat.
- Arm, disarm and save still clear `skipNextDate`, as today.
- A skip is "pending" only while `skipNextDate` ≥ today. An old date is harmless and ignored for display, because `NextOccurrence` only ever compares it with future dates.
- The skipped occurrence is closed as **`SKIPPED`**. Event log: `SKIP` / `UNSKIP`.
- **Sleep is unaffected.** No anchor is written.

### I'M UP (phone SLP mode, watch SLP mode, watch tile)

The existing bed control becomes a two-state toggle, like a stopwatch's start/stop:

| State (`nextSleepAction`) | Phone SLP · SET | Watch SLP · hold 2 s / tile button | Result |
| --- | --- | --- | --- |
| `BED` | `GOOD NIGHT` | `BED` | Records a BED anchor (as today) |
| `UP` | `GOOD MORNING` | `UP` | Records a WAKE anchor and closes today's targets (below) |

- **State rule:** `UP` when the latest BED anchor falls in the current night window (18:00–14:00, `NightWindow`) and no WAKE anchor follows it. Otherwise `BED`. So in the 14:00–18:00 gap it always shows `BED`, and a bed press left from days ago doesn't leave it stuck on `UP`.
- **Targets** (`imUpTargets`): every non-terminal occurrence due **today before 14:00** local, on any channel, **including one already in its stages**. Pressing at 22:00 therefore never touches tomorrow's alarm.
- Each target is closed as **`DISMISSED`** with `dismissedAt` = press time and `dismissedOn` = the pressing device. This is a dismiss before ringing: it stops running stages on both devices, and a one-shot channel disarms, exactly as a normal dismiss does.
- If there are no targets, I'M UP just records the WAKE anchor.

### Closing occurrences on edit or disarm (#5, #6)

A new terminal state, **`CANCELLED`**, means "closed because the channel changed, not because anything happened." `ScheduleBuilder.decide` returns it as `cancelled` when the channel's non-terminal occurrence (`SCHEDULED` or `FIRING`) is no longer what the channel wants:

- **Disarmed:** the alarm is disabled.
- **Edited:** the recomputed occurrence has a different ID (the time moved).

An edit that keeps the time (profile, tone, target, label, or repeat days that still include that day) keeps the occurrence, as today. A ringing occurrence whose channel is unchanged is kept, as today.

On the phone, `rescheduleAll` persists the cancelled occurrence. If it was `FIRING`, the phone stops its own `WakeService` and sends a close command to the watch, which stops `WakeStageService`. The close goes through the existing dismiss transport (message + `/instance/{id}/state` item) with the command carrying the closing state (below).

A one-shot channel disarms on its first **DISMISSED or SILENCED** transition only. `SKIPPED` and `CANCELLED` never disarm it; this stops an edit of a one-shot's time from disarming it.

### Wake behaviour (#7)

Each occurrence records two new facts:

- `firstStageAtEpochMs`: when its first stage started (set once, by the first `StageStarted`).
- `dismissedAtStage`: the stage that was running when it was dismissed (the reducer keeps it instead of discarding it).

`WakeBehaviour.of(instance)` turns a closed occurrence into one of:

| Result | When | SLP lap line |
| --- | --- | --- |
| `Dismissed(stage, minutes)` | DISMISSED after its first stage started | `WOKE SND +6` (LIT / BUZ / SND / MAX) |
| `UpEarly` | DISMISSED with no stage started (I'M UP) | `UP EARLY` |
| `Skipped` | SKIPPED | `SKIPPED` |
| `NoAnswer` | SILENCED | `NO ANSWER` |
| none | CANCELLED, or not closed | no line |

The SLP lap shows it for the first real occurrence (channel ≥ 1) scheduled in that night's window, on the line below `BED … UP …`. A watch dismiss records the stage as well; it syncs to the phone through the snapshot state the phone already reconciles.

### Inferred wake (#1)

For a night with a `SKIPPED` occurrence and no WAKE anchor, `rebuild` infers one:

- **Candidates:** phone unlocks (`UsageStatsManager` `KEYGUARD_HIDDEN` events) and watch "awake" samples (`SleepSignal` WATCH, `asleep = false`).
- **Rule** (`WakeInference.infer`): the first candidate at or after the skipped alarm time and before the night window ends, provided it is less than 24 h after the night's start (the BED anchor, else the skipped alarm time). The night window already ends at 14:00, so the 24 h bound is a guard, kept explicit as decided in #1.
- The inferred time is used as the night's wake anchor for that build only (not stored), so a later Health Connect session or a real I'M UP press still wins. The event log gets `WAKE_INFERRED` with the source.
- **Permission:** reading unlocks needs Usage access (`PACKAGE_USAGE_STATS`, granted in Settings). CHK gets a new `USE` item that opens the setting. Without it, only watch "awake" samples are used.
- A night with no BED anchor still has no anchors-only session; this rule only supplies the wake end.

## Architecture

### Pure logic (`:core`)

| Unit | Module | Signature (sketch) | Rule |
| --- | --- | --- | --- |
| `InstanceState` (changed) | `core/model` | + `CANCELLED` (terminal) | — |
| `AlarmInstance` (changed) | `core/model` | + `firstStageAtEpochMs: Long? = null`, `dismissedAtStage: StageType? = null` | — |
| `Command` (changed) | `core/model` | + `state: InstanceState = DISMISSED` | The closing state a receiver applies |
| `SleepSummary` (changed) | `core/model` | + `lastBedAnchorEpochMs: Long? = null`, `lastWakeAnchorEpochMs: Long? = null` | The watch's BED/UP toggle |
| `WakeAnchorMessage` (new) | `core/model` | `(atEpochMs: Long)` | `/sleep/wake`, watch → phone |
| `WakeStateMachine.reduce` (changed) | `core/wake` | unchanged | Sets `firstStageAt` once; Dismiss keeps `dismissedAtStage` |
| `ScheduleBuilder.decide` (changed) | `core/wake` | `ChannelDecision(active, missed, skipped, cancelled)` | Branches below |
| `ScheduleBuilder.current` | `core/wake` | `(rows: List<AlarmInstance>) -> AlarmInstance?` | Latest by scheduled time, ignoring `CANCELLED` and `SKIPPED` |
| `imUpTargets` | `core/wake` | `(entries, now, zone) -> List<ScheduleEntry>` | Non-terminal, channel ≥ 1, due on `now`'s local date before 14:00 |
| `WakeBehaviour.of` | `core/wake` | `(AlarmInstance) -> WakeBehaviour?` | Table above |
| `nextSleepAction` | `core/sleep` | `(lastBed, lastWake, now, zone) -> SleepAction` | `UP` if `lastBed` is in the current night window and not followed by `lastWake` |
| `WakeInference.infer` | `core/sleep` | `(skippedAt, candidates, nightStart, window) -> Instant?` | Inferred wake rule |

**`decide`, in order.** `T` = `last`'s scheduled time, `date(T)` its local date, `live` = non-terminal and before its silence time.

1. **Ringing:** `live`, `FIRING`, channel enabled and its time unchanged → keep (as today).
2. **Ringing, channel changed:** `live`, `FIRING`, and disabled or time changed → `cancelled`; search after `max(now, T)`.
3. **Skip:** `live`, `SCHEDULED`, `date(T) == skipNextDate` → `skipped`; search after `T`.
4. **Overdue unchanged:** as today (keep, so it rings late).
5. **Recompute:** `live` otherwise → search from `now`. Same ID → keep `last` as is. Null or a different ID → the new one is active and `last` is `cancelled`.
6. Existing missed and terminal handling unchanged.

**Which row is the channel's current occurrence.** Today `rescheduleAll` passes `decide` the channel's row with the latest scheduled time. Once replaced occurrences are closed as `CANCELLED`, that row can be a cancelled future one: move 7:00 to 6:30 and the cancelled 7:00 row would make the next run roll past 6:30. So `ScheduleBuilder.current(rows)` picks the latest row, ignoring `CANCELLED` **and `SKIPPED`** rows: a closed future row must never be the channel's current occurrence. A skipped one would lose the skipped day: skip Thursday, disarm (which clears the skip), re-arm, and a `SKIPPED` Thursday row as `last` would make the recompute start after Thursday and arm Friday. `rescheduleAll` passes the result as `last`. A cancelled or skipped row's ID can be reused: when the recompute lands on it again, `rescheduleAll` overwrites it as `SCHEDULED`.

**Undo needs no branch of its own.** After a skip, the channel's latest occurrence is the next one (say Thursday), not the skipped Wednesday. Clearing the skip makes the recompute find Wednesday again. Its deterministic ID is the skipped row's, which `rescheduleAll` overwrites as `SCHEDULED`, and Thursday is closed as `CANCELLED`; it's recreated when Wednesday is over. `ScheduleMerge` needs no change: phone snapshots only carry active occurrences, so a watch never holds a phone-sent `SKIPPED` copy.

### Data changes

| Item | Change |
| --- | --- |
| Room `alarm_instance` | + `firstStageAt INTEGER`, `dismissedAtStage TEXT` (nullable). `@AutoMigration(from = 1, to = 2)`; schema JSON exported to `app-phone/schemas` |
| `Alarm.skipNextDate` | None. Already in the model, Room and `NextOccurrence` |
| `/sleep/wake` | New message path, watch → phone, through the outbox. Mirrors `/sleep/bedtime` |
| `SleepSyncWorker` | + a daily periodic job ("sleep-daily", unique, KEEP) that runs around 11:00, rebuilds the last two nights and publishes the summary. Enqueued at app start |
| Phone manifest | + `PACKAGE_USAGE_STATS` (`tools:ignore="ProtectedPermission"`) |
| JSON export | Instances gain `firstStageAt`, `dismissedAtStage` |

The watch and phone must be updated together: an older app can't decode `CANCELLED`.

### Flows

**SKIP (phone):** hold ▼ → `AlarmRepository.setSkip(channel, date | null)` saves the alarm, keeping it armed → `rescheduleAll`:
1. `decide` returns `skipped`; `markInstance(SKIPPED)`; event log `SKIP`.
2. The next occurrence is registered.
3. The snapshot is pushed. The skipped entry is no longer active, so the watch cancels its alarms.

**Edit or disarm (phone):** `rescheduleAll` → `decide` returns `cancelled` → `markInstance(CANCELLED)`, event log `CANCELLED`. If it was `FIRING`: `WakeService.remoteCommands.tryEmit(close)` and `PhoneSync.sendDismiss(…, Command(id, now, PHONE, CANCELLED))`.

**I'M UP (phone):** SET in `UP` state → `WakeUp.record(at, PHONE)`:
1. `SleepRepository.onWake(at)`: WAKE anchor, rebuild the night, queue the +30 min / +2 h / 11:00 syncs. Once per press, with or without targets.
2. For each `imUpTargets(snapshot)`: reduce with `Dismiss(PHONE)`, `InstanceEvents.record(…, stampWake = false)`, then `PhoneSync.sendDismiss`, the same messages a phone dismiss sends. A running `WakeService` stops through `remoteCommands`.
3. `rescheduleAll`, then `publishSummary`. Event log `IM_UP` with the number of targets.

**I'M UP (watch):** hold in `UP` state (SLP mode or tile) → on the watch:
1. `imUpTargets(WatchScheduleStore)`. For each target: reduce with `Dismiss(WATCH)`, update the store, `cancelChannel`, stop `WakeStageService` if it's running, and `WatchSync.sendDismiss` (existing DataItem plus outbox message).
2. Send `/sleep/wake` through the outbox; record the local wake time for the toggle.
3. Refresh surfaces.

On the phone, the existing `remoteDismiss` closes each target and calls `onWake(sentAt)`, and the `/sleep/wake` handler calls `onWake(at)`, so a night can get two WAKE anchors at the same instant. That's harmless: `rebuild` uses the latest.

**Receiving a close command (either device):** apply `cmd.state` instead of always `DISMISSED`. `dismissedAt`/`dismissedOn` are set only for `DISMISSED`. The phone calls `onWake` only for `DISMISSED` (as today).

**Watch toggle state:** `nextSleepAction(max(summary.lastBed, localBed), max(summary.lastWake, localWake), now)`. The phone fills the two summary fields from its latest BED and WAKE anchors and publishes the summary after every BED or WAKE anchor.

**Sleep.** `SessionBuilder` is unchanged. A dismiss, I'M UP and an inferred wake all feed the same `Anchors.dismissedAt`, which is both the anchors-only session's end and the cutoff (`horizon`) for sleep samples.

## Error handling and edge cases

| Case | Behaviour |
| --- | --- |
| I'M UP on the watch, phone unreachable | Watch stops its own targets at once; the dismisses and `/sleep/wake` wait in the outbox. If the phone's own alarm is due before the outbox flushes, the phone still rings. The watch alone can't stop the phone (same as a watch dismiss today, F5) |
| I'M UP after the phone's stages started | Target is DISMISSED; `WakeService` stops through `remoteCommands` |
| SKIP undone after the watch already cancelled | The phone reopens the occurrence and pushes the snapshot; the watch has no closed copy of it, so it re-registers |
| SKIP then I'M UP the same morning | The skipped occurrence is already terminal, so it's not a target. I'M UP only records the WAKE anchor |
| Channel disarmed while the watch rings but the phone never started (its ring service failed) | The phone's occurrence is `SCHEDULED`, so it's cancelled and the close command is sent only for `FIRING`. The watch's alarms are cancelled by the snapshot, but a running stage continues until its hold or auto-silence. Accepted: the phone starts first in every profile |
| Unlock at 03:00, skipped alarm 06:30, next unlock 09:10 | Inferred wake 09:10 (candidates before the skipped time are ignored) |
| Usage access not granted | Only watch "awake" samples are candidates; CHK `USE` blinks |
| Skipped night, no bedtime, no HC, no samples | No session. Hand edit is the workaround |
| DST night | `NightWindow` already handles 19- and 21-hour nights. `imUpTargets` uses the local date and time |

## Testing

Following `~/.claude/skills/test-selection`: tests only for pure decisions that could fail silently and matter. Everything that touches the OS goes to device checks. Each test names what it guards.

**Unit tests (`:core`)**

| Test | Guards |
| --- | --- |
| `decide`: a pending occurrence on `skipNextDate` is closed `SKIPPED` and the next repeat day becomes active | Skip prevents ringing |
| `decide`: with Thursday pending after a Wednesday skip, clearing the skip makes Wednesday's ID active again and cancels Thursday | Undo works |
| `decide`: a FIRING occurrence on `skipNextDate` is left ringing | Skip never cuts off a running wake-up |
| `decide`: disarming during FIRING returns it `cancelled` | #6 |
| `decide`: moving the time of a SCHEDULED occurrence returns the old one `cancelled`; changing only the profile keeps it | #5, and no spurious cancel |
| `decide` + `current`: after moving 7:00 to 6:30, the next run keeps 6:30 active (the cancelled 7:00 row is not current) | Moving an alarm earlier still rings today |
| `decide` + `current`: skip Thursday, disarm, re-arm → Thursday's ID is active again (the `SKIPPED` row is not current) | A skip followed by disarm and re-arm doesn't lose the skipped day |
| `imUpTargets`: 05:00 press → today's 06:30 is a target; 22:00 press → tomorrow's 06:30 is not; 13:59 vs 14:00 boundary; channel 0 test alarm excluded | Same-day, before-14:00 rule |
| `nextSleepAction`: BED in window with no WAKE → UP; WAKE after → BED; BED from the previous night → BED; 14:00–18:00 → BED | The toggle never gets stuck on UP |
| `WakeStateMachine`: `firstStageAt` is set by the first stage only; a dismiss keeps `dismissedAtStage` | #7 data |
| `WakeBehaviour.of`: dismissed in SOUND 6 min after the first stage; dismissed before any stage → `UpEarly` | #7, and I'M UP kept apart |
| `WakeInference.infer`: ignores candidates before the skipped time; picks the earliest after; rejects one ≥ 24 h after the night start and one outside the window | #1 |

**Device checks** (added to the README UAT table)

| # | Check | Pass |
| --- | --- | --- |
| 13 | ALM → hold ▼ on a weekday channel | `SKIP <day>`; watch ALM shows the following day |
| 14 | Hold ▼ again | Skip cleared on both devices |
| 15 | Set AL1 for +15 min, SLP → GOOD NIGHT, then GOOD MORNING before it rings | No ring on either device; SLP shows the wake time as the press time and `UP EARLY` |
| 16 | As 15, but press UP on the watch with the phone's Bluetooth off | Watch doesn't buzz; after reconnecting, the phone's alarm is closed (if not yet rung) |
| 17 | Skip tomorrow's alarm; next day, don't open the app until after 11:00 | Session present with the OH badge (if OHealth synced), or ending at your first unlock after the alarm time |
| 18 | Set AL1 for +11 min (Gentle). Once the phone's sunrise starts, ALM → hold SET to disarm AL1 | Sunrise stops at once; the watch doesn't buzz at T−3 |
| 19 | Next morning after a normal dismiss: SLP | `WOKE <stage> +<min>` under BED/UP |
| 21 | Skip tomorrow on AL1, disarm AL1, re-arm it | Countdown points at tomorrow again, and it rings |

## Still open and tracked

| Issue | Type | Summary |
| --- | --- | --- |
| [#3](https://github.com/trepidity/GOOD/issues/3) | Decision | SKIP from the watch |
| [#8](https://github.com/trepidity/GOOD/issues/8) | Known issue | Watch passive asleep state unverified on the 2R; limits how often watch "awake" can supply an inferred wake |

## SPEC.md updates when this lands

- Requirements: add F13 (SKIP next occurrence), F14 (I'M UP), both Should.
- UX design → ALM row: hold ▼ = skip or unskip. SLP row: SET = GOOD NIGHT or GOOD MORNING; the wake-behaviour line. CHK: `USE`.
- Sleep tracking: step 2 "On dismiss **or I'M UP**…"; inferred wake for skipped nights; daily 11:00 sync; the wake-behaviour metric as built.
- Data Layer table: `/sleep/wake`; `Command.state`; `SleepSummary` anchor fields.
- Data model: `CANCELLED`; `firstStageAt`, `dismissedAtStage`; `SKIPPED` written by SKIP only; I'M UP closes as `DISMISSED` before ringing.
- Permissions: `PACKAGE_USAGE_STATS`.
