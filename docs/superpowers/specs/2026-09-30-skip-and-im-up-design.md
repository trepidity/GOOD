# Skip next and I'M UP — Design

As of 2026-09-30 · Jared · Extends [SPEC.md](../../SPEC.md) (F1, F4, F6) · Tracking labels: `feature: skip & I'M UP`, `decision: open`, `known-issue`

## Goal

Two controls for mornings that don't go to plan:

- **SKIP**: no alarm on the next occurrence of a repeating channel, **but you're still asleep**. Sleep is tracked as usual, so sleeping in counts.
- **I'M UP**: you're awake and staying up. It records your wake time and cancels any alarm still due this morning.

They are separate because they mean opposite things for sleep: SKIP says "keep counting," I'M UP says "stop counting now."

**Success criteria**

- A skipped occurrence never rings on either device, and undoing the skip before the day restores it on both.
- I'M UP pressed on the watch with the phone out of range stops the watch's alarm immediately. When the phone comes back, the phone's alarm is cancelled too if it hasn't rung yet.
- An early wake is recorded at the I'M UP time, not the alarm time, when no better source exists.
- A skipped night still gets a session by 11:00 whenever Health Connect or sleep samples have data.

## Behaviour

### SKIP (phone, ALM mode)

| Input | Result |
| --- | --- |
| Hold **▼** 2 s on a repeating channel whose next occurrence is `SCHEDULED` | `skipNextDate` = that occurrence's local date. Banner `SKIPPED`. Status line: `SKIP WED · NEXT THU 6:30 · GENTLE` |
| Hold **▼** 2 s while a skip is pending (`skipNextDate` ≥ today) | Skip cleared. Banner `UNSKIP`. The occurrence returns |
| Hold **▼** 2 s on a one-shot channel | No change. Banner `ONCE — USE OFF` |
| Hold **▼** 2 s while the occurrence is ringing | No change. The ring screen's STOP is the control |

- Outside edit mode, ▼ no longer repeats when held, so the 2-s hold doesn't cycle channels. ▲ is unchanged.
- Arm, disarm and save still clear `skipNextDate`, as today.
- A skip is "pending" only while `skipNextDate` ≥ today. An old date is harmless and ignored for display, because `NextOccurrence` only ever compares it with future dates.
- The skipped occurrence is closed as **`SKIPPED`**, the first real use of that state. The event log gets `SKIP` / `UNSKIP` entries.
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

## Architecture

### Pure logic (`:core`)

| Unit | Module | Signature (sketch) | Rule |
| --- | --- | --- | --- |
| `ScheduleBuilder.decide` (changed) | `core/wake` | `ChannelDecision(active, missed, skipped)` | New branches, below |
| `imUpTargets` | `core/wake` | `(entries: List<ScheduleEntry>, now: Instant, zone: ZoneId) -> List<ScheduleEntry>` | Non-terminal, due on `now`'s local date, before 14:00 |
| `nextSleepAction` | `core/sleep` | `(lastBed: Instant?, lastWake: Instant?, now: Instant, zone: ZoneId) -> SleepAction` | `UP` if `lastBed` is in the current night window and not followed by `lastWake` |
| `ScheduleMerge.merge` (changed) | `core/sync` | unchanged | A locally closed `SKIPPED` entry no longer overrides the incoming snapshot, because the phone owns skips |

**`decide` changes.** `T` = `last`'s scheduled time, and `date(T)` = its local date.

1. **Skip:** the channel's pending occurrence is `SCHEDULED`, still live, and `date(T) == alarm.skipNextDate`. Close it as `skipped` (`state = SKIPPED`), then search for the next occurrence after `T`. This check comes after the FIRING check, so a ringing occurrence is never skipped.
2. **Undo:** `last.state == SKIPPED`, `T` is still in the future, and `date(T) != alarm.skipNextDate`. Search again from `now`. `NextOccurrence` finds `T` again, and the occurrence gets the same deterministic ID with state `SCHEDULED`.

Both branches depend on `SKIPPED` coming from SKIP only. I'M UP uses `DISMISSED`, so undoing a skip can never revive an alarm that I'M UP cancelled.

`ScheduleMerge`'s exception for `SKIPPED` matters for undo. The watch may hold a phone-sent `SKIPPED` copy of an occurrence, and without the exception it would keep that copy closed after the phone reopens it.

### Data changes

| Item | Change |
| --- | --- |
| `Alarm.skipNextDate` | None. It already exists in the model, Room and `NextOccurrence` |
| `InstanceState.SKIPPED` | Now written, by `rescheduleAll` from `decide().skipped` |
| `SleepSummary` (phone → watch DataItem) | + `inBedSinceEpochMs: Long?`: the latest BED anchor not followed by a WAKE, so the watch shows `UP` after a phone bed press |
| New message `/sleep/wake` | `WakeAnchorMessage(atEpochMs)`, watch → phone, through the outbox. Mirrors `/sleep/bedtime` |
| `SleepSyncWorker` | + a daily periodic job ("sleep-daily", unique, KEEP) that runs around 11:00 and rebuilds the last two nights. Enqueued at app start and by `rescheduleAll` |

No Room migration is needed.

### Flows

**SKIP (phone):** hold ▼ → `AlarmRepository.setSkip(channel, date | null)` saves the alarm without clearing arm state → `rescheduleAll`:
1. `decide` returns `skipped`; `markInstance(SKIPPED)` closes it and the event log gets `SKIP`.
2. The next occurrence is registered.
3. The snapshot is pushed. The skipped entry is no longer active, so the watch cancels its alarms.

**I'M UP (phone):** SET in `UP` state → `WakeUp.record(at, PHONE)`:
1. `SleepRepository.onWake(at)`: WAKE anchor, rebuild the night, queue the +30 min / +2 h / 11:00 syncs. This runs once per press, whether or not there are targets.
2. For each `imUpTargets(snapshot)`: `InstanceEvents.record(DISMISSED, at, PHONE)` without its own `onWake` call, then `PhoneSync.sendDismiss`, the same messages a phone dismiss sends. A running `WakeService` stops.
3. `rescheduleAll`, then `publishSummary` (the `inBedSince` field clears).

**I'M UP (watch):** hold in `UP` state (SLP mode or tile) → on the watch:
1. `imUpTargets(WatchScheduleStore)`. For each target: mark it `DISMISSED(WATCH)`, call `cancelChannel`, stop `WakeStageService` if it's running, and call `WatchSync.sendDismiss` (existing DataItem plus outbox message).
2. Send `/sleep/wake` through the outbox.
3. Clear the local "in bed" state and refresh the tile.

On the phone, the existing `remoteDismiss` closes each target and calls `onWake(sentAt)`. The `/sleep/wake` message calls `onWake(at)` as well, so a night can get two WAKE anchors at the same instant. That's harmless, because `rebuild` uses the latest one.

**Sleep.** `SessionBuilder` is unchanged. A dismiss and I'M UP write the same WAKE anchor, which is both the anchors-only session's end and the cutoff (`horizon`) for sleep samples. A skipped night without I'M UP has no WAKE anchor, so it depends on Health Connect or samples. The daily 11:00 job makes sure those are read even if the app isn't opened.

## Error handling and edge cases

| Case | Behaviour |
| --- | --- |
| I'M UP on the watch, phone unreachable | Watch stops its own targets at once; the dismisses and `/sleep/wake` wait in the outbox. If the phone's own alarm is due before the outbox flushes, the phone still rings. The watch alone can't stop the phone (same as a watch dismiss today, F5) |
| I'M UP after the phone's stages started | Target is DISMISSED; `WakeService` stops through the existing remote/user dismiss path |
| SKIP undone after the watch already cancelled | The phone reopens the occurrence and pushes the snapshot; the watch accepts it (merge exception) and re-registers |
| SKIP then I'M UP the same morning | The skipped occurrence is already terminal, so it's not a target. I'M UP only records the WAKE anchor |
| I'M UP pressed twice | The second press finds state `BED` and logs a bedtime, which is the toggle working as designed. A second WAKE can only come from a watch press racing a phone press. It's harmless |
| Skipped night, no I'M UP, no HC, no samples | No session. **Accepted gap** (#1). Hand edit is the workaround |
| DST night | `NightWindow` already handles 19- and 21-hour nights. `imUpTargets` uses the local date and time |

## Testing

Following `~/.claude/skills/test-selection`: tests only for pure decisions that could fail silently and matter. Everything that touches the OS goes to device checks.

**Unit tests (`:core`)**

| Test | Guards |
| --- | --- |
| `decide`: a pending occurrence on `skipNextDate` is closed `SKIPPED` and the next repeat day becomes active | Skip actually prevents ringing |
| `decide`: clearing the skip before `T` reopens the same occurrence ID as `SCHEDULED` | Undo works |
| `decide`: a FIRING occurrence on `skipNextDate` is left ringing | Skip never cuts off a running wake-up silently |
| `imUpTargets`: 05:00 press → today's 06:30 is a target; 22:00 press → tomorrow's 06:30 is not; 13:59 vs 14:00 boundary | The same-day, before-14:00 rule |
| `nextSleepAction`: BED in window with no WAKE → UP; WAKE after → BED; BED from the previous night → BED | The toggle never gets stuck on UP |
| `ScheduleMerge`: a local `SKIPPED` doesn't override an incoming `SCHEDULED`; a local `DISMISSED` still does | Undo reaches the watch, and watch dismisses stay closed |

**Device checks** (add to the README UAT table)

| # | Check | Pass |
| --- | --- | --- |
| 13 | ALM → hold ▼ on a weekday channel | `SKIP <day>`; watch ALM shows the following day |
| 14 | Hold ▼ again | Skip cleared on both devices |
| 15 | Set AL1 for +15 min, SLP → GOOD NIGHT, then GOOD MORNING before it rings | No ring on either device; SLP shows the wake time as the press time |
| 16 | As 15, but press UP on the watch with the phone's Bluetooth off | Watch doesn't buzz; after reconnecting, the phone's alarm is closed (if not yet rung) |
| 17 | Skip tomorrow's alarm; next day, don't open the app until after 11:00 | Session present with the OH badge (if OHealth synced) |

## Out of scope and tracked

| Issue | Type | Summary |
| --- | --- | --- |
| [#1](https://github.com/trepidity/GOOD/issues/1) | Decision | Skipped night with no wake data records no session (accepted gap) |
| [#2](https://github.com/trepidity/GOOD/issues/2) | Decision | Detect being awake automatically and silence the alarm |
| [#3](https://github.com/trepidity/GOOD/issues/3) | Decision | SKIP from the watch |
| [#4](https://github.com/trepidity/GOOD/issues/4) | Decision | Skip more than one date |
| [#5](https://github.com/trepidity/GOOD/issues/5) | Known issue | Editing an alarm leaves its old occurrence open |
| [#6](https://github.com/trepidity/GOOD/issues/6) | Known issue | Disarming or editing doesn't stop stages already running |
| [#7](https://github.com/trepidity/GOOD/issues/7) | Known issue | Wake-behaviour metrics never built; must exclude I'M UP's early dismisses when built |
| [#8](https://github.com/trepidity/GOOD/issues/8) | Known issue | Watch passive asleep state unverified on the 2R |

## SPEC.md updates when this lands

- Requirements: add F13 (SKIP next occurrence) and F14 (I'M UP), both Should.
- UX design → ALM row: hold ▼ = skip or unskip. SLP row: SET = GOOD NIGHT or GOOD MORNING.
- Sleep tracking step 2: "On dismiss **or I'M UP**…". Add the daily 11:00 sync.
- Data Layer table: `/sleep/wake`. `SleepSummary.inBedSinceEpochMs`.
- Data model: `SKIPPED` written by SKIP only; I'M UP closes as `DISMISSED` before ringing.
