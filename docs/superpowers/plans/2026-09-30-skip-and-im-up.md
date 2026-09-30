# Skip next and I'M UP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add SKIP (skip a repeating alarm's next occurrence without affecting sleep), I'M UP (a GOOD MORNING toggle that records the wake time and closes today's alarms), an inferred wake time for skipped nights, closing occurrences on edit or disarm (stopping a running wake-up), and the wake-behaviour metric.

**Architecture:** All decisions live in pure `:core` functions with unit tests (`ScheduleBuilder.decide`/`current`, `ImUp.targets`, `WakeBehaviour.of`, `SleepToggle.next`, `WakeInference.infer`, the reducer). The phone and watch apps wire those decisions to Room, AlarmManager, the Data Layer and the UI, reusing the existing dismiss transport for every "close" (a `Command` now carries the closing state).

**Tech Stack:** Kotlin, Jetpack Compose, Room 2 (KSP, exported schemas, AutoMigration), WorkManager, Wearable Data Layer, Wear Tiles, kotlinx.serialization, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-30-skip-and-im-up-design.md` (rev. 2). Read it before your task.

## Global Constraints

- Phone `minSdk = 34`; watch `minSdk = 33`. No new third-party dependencies.
- No INTERNET permission; GOOD stays offline.
- LCD text goes through `lcd()` (phone), which keeps only letters, digits and ` -+/*:.·`. Never use an em dash or other punctuation in LCD strings.
- There is no snooze, anywhere. Don't add one.
- Every new test names the gate/requirement it protects in a KDoc or class doc, following `~/.claude/skills/test-selection/SKILL.md`. Don't test getters, serialization round-trips of plain data, or Android framework behaviour.
- Kotlin style: match the surrounding code — KDoc on public objects/functions that explain *why*, no redundant comments, expression bodies where the file uses them.
- Commit after each task with a message ending in `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Test command for core: `./gradlew :core:wake:test :core:sync:test :core:sleep:test --offline`
- Build + lint command for apps: `./gradlew :app-phone:assembleDebug :app-wear:assembleDebug :app-phone:lintDebug :app-wear:lintDebug --offline`

## Review Focus

1. **Moving an alarm earlier on the same day** (7:00 → 6:30 at 22:00) must still ring at 6:30 after any number of later `rescheduleAll` runs. Pinned by the `current` test in Task 1 and wired in Task 3 Step 3.
2. **A single tap on ▼ in ALM mode** still switches channel; only a 2-s hold skips. `CaseButton` treats a quick release as a click when `onLongClick` is set. Checked on device (UAT 13) in Task 5.
3. **Upgrading an installed v1 database** keeps all alarms, instances and sleep data (AutoMigration 1→2). Verified by building with the exported `2.json` and by the device check in Task 7's UAT row 20.
4. **I'M UP pressed on both devices within seconds** produces two WAKE anchors and at most one close per occurrence; no crash, and the phone's toggle shows BED afterwards. Idempotency comes from `markInstance` ignoring writes to terminal rows and the listeners skipping terminal entries (Task 3, Task 6).
5. **Phone and watch on different app versions** can't decode `CANCELLED`. Documented in the README; both apps are always installed together (Task 7).

---

## File map

| File | Change | Task |
| --- | --- | --- |
| `core/model/src/main/kotlin/com/trepidity/good/model/Model.kt` | `CANCELLED`, instance fields, `Command.state`, summary fields, `WakeAnchorMessage` | 1 |
| `core/wake/src/main/kotlin/com/trepidity/good/wake/WakeStateMachine.kt` | first-stage time, stage at dismiss | 1 |
| `core/wake/src/main/kotlin/com/trepidity/good/wake/ScheduleBuilder.kt` | `ChannelDecision` + skip/cancel branches, `current` | 1 |
| `core/wake/src/main/kotlin/com/trepidity/good/wake/ImUp.kt` | new: I'M UP targets | 1 |
| `core/wake/src/main/kotlin/com/trepidity/good/wake/WakeBehaviour.kt` | new: wake-behaviour metric | 1 |
| `core/sync/src/main/kotlin/com/trepidity/good/sync/DataLayer.kt` | `SLEEP_WAKE` path | 1 |
| `core/wake/src/test/.../ScheduleBuilderTest.kt`, `WakeStateMachineTest.kt`, new `ImUpTest.kt`, `WakeBehaviourTest.kt` | tests | 1 |
| `core/sleep/src/main/kotlin/com/trepidity/good/sleep/SleepToggle.kt` | new: BED/UP toggle | 2 |
| `core/sleep/src/main/kotlin/com/trepidity/good/sleep/WakeInference.kt` | new: inferred wake | 2 |
| `core/sleep/src/test/.../SleepToggleTest.kt`, `WakeInferenceTest.kt` | tests | 2 |
| `app-phone/.../data/Entities.kt`, `Daos.kt`, `GoodDatabase.kt`, `app-phone/schemas/.../2.json` | Room v2 | 3 |
| `app-phone/.../alarm/AlarmRepository.kt`, `InstanceEvents.kt` | skip, cancel, close, one-shot rule | 3 |
| `app-phone/.../sync/PhoneListenerService.kt` | apply `cmd.state` | 3 |
| `app-phone/.../DataExport.kt` | new instance fields | 3 |
| `app-phone/.../alarm/WakeUp.kt` | new: phone I'M UP flow | 4 |
| `app-phone/.../sleep/SleepRepository.kt`, `SleepSyncWorker.kt`, new `UnlockLog.kt` | anchors, summary, inferred wake, daily sync | 4 |
| `app-phone/.../sync/PhoneListenerService.kt` | `/sleep/wake` | 4 |
| `app-phone/.../ReliabilityCheck.kt`, `AndroidManifest.xml` | CHK `USE`, permission | 4 |
| `app-phone/.../ui/InstrumentModel.kt`, `Instrument.kt` | hold ▼, status line, SLP toggle, wake line | 5 |
| `app-wear/build.gradle.kts` | + `:core:sleep` | 6 |
| `app-wear/.../sync/WatchListenerService.kt` | apply `cmd.state` | 6 |
| `app-wear/.../alarm/WatchScheduleStore.kt`, new `app-wear/.../sleep/WatchImUp.kt` | local anchors, I'M UP flow | 6 |
| `app-wear/.../ui/WatchApp.kt`, `surface/GoodTileService.kt` | BED/UP toggle | 6 |
| `docs/SPEC.md`, `README.md`, `docs/REVIEW.md` | docs, UAT rows | 7 |

Dependencies: 1 → 3 → 4 → 5; 2 → 4; 1 + 2 → 6; all → 7. Task 2 can run beside Task 1. Task 6 can run beside Tasks 4–5 (disjoint modules) once 1–3 are merged.

---

### Task 1: Core model and wake decisions

**Files:**
- Modify: `core/model/src/main/kotlin/com/trepidity/good/model/Model.kt`
- Modify: `core/wake/src/main/kotlin/com/trepidity/good/wake/WakeStateMachine.kt`
- Modify: `core/wake/src/main/kotlin/com/trepidity/good/wake/ScheduleBuilder.kt`
- Create: `core/wake/src/main/kotlin/com/trepidity/good/wake/ImUp.kt`
- Create: `core/wake/src/main/kotlin/com/trepidity/good/wake/WakeBehaviour.kt`
- Modify: `core/sync/src/main/kotlin/com/trepidity/good/sync/DataLayer.kt`
- Test: `core/wake/src/test/kotlin/com/trepidity/good/wake/ScheduleBuilderTest.kt`, `WakeStateMachineTest.kt`, create `ImUpTest.kt`, `WakeBehaviourTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `InstanceState.CANCELLED` (terminal).
  - `AlarmInstance.firstStageAtEpochMs: Long? = null`, `AlarmInstance.dismissedAtStage: StageType? = null`.
  - `Command(instanceId, sentAtEpochMs, from, state: InstanceState = InstanceState.DISMISSED)`.
  - `SleepSummary(..., lastBedAnchorEpochMs: Long? = null, lastWakeAnchorEpochMs: Long? = null)`.
  - `@Serializable data class WakeAnchorMessage(val atEpochMs: Long)`.
  - `DataLayerPaths.SLEEP_WAKE = "/sleep/wake"`.
  - `ChannelDecision(active: AlarmInstance?, missed: AlarmInstance? = null, skipped: AlarmInstance? = null, cancelled: AlarmInstance? = null)`.
  - `ScheduleBuilder.current(rows: List<AlarmInstance>): AlarmInstance?`.
  - `ImUp.targets(entries: List<ScheduleEntry>, now: Instant, zone: ZoneId): List<ScheduleEntry>`.
  - `sealed interface WakeBehaviour { Dismissed(stage: StageType, minutesAfterFirstStage: Long); UpEarly; Skipped; NoAnswer }` and `WakeBehaviour.of(instance: AlarmInstance): WakeBehaviour?`.

- [ ] **Step 1: Model changes.** In `Model.kt`:

```kotlin
@Serializable
enum class InstanceState { SCHEDULED, FIRING, DISMISSED, SILENCED, SKIPPED, CANCELLED;
    /** CANCELLED = closed because its channel was edited or disarmed, not because anything happened. */
    val isTerminal: Boolean get() = this != SCHEDULED && this != FIRING
}

/** One occurrence of an alarm. [scheduledAtEpochMs] is the alarm time T. */
@Serializable
data class AlarmInstance(
    val id: String,
    val alarmId: Long,
    val scheduledAtEpochMs: Long,
    val state: InstanceState = InstanceState.SCHEDULED,
    val currentStage: StageType? = null,
    val dismissedAtEpochMs: Long? = null,
    val dismissedOn: Device? = null,
    /** When the first stage started; null if none did (e.g. closed by I'M UP before ringing). */
    val firstStageAtEpochMs: Long? = null,
    /** The stage that was running when it was dismissed; null for a dismiss before any stage. */
    val dismissedAtStage: StageType? = null,
)
```

`Command` gains a last parameter with a default so old payloads still decode:

```kotlin
/** Dismiss messages exchanged over the Data Layer, and the `/instance/{id}/state` catch-up item. */
@Serializable
data class Command(
    val instanceId: String,
    val sentAtEpochMs: Long,
    val from: Device,
    /** The state the receiver closes the occurrence in: DISMISSED, or CANCELLED when its channel was disarmed or edited. */
    val state: InstanceState = InstanceState.DISMISSED,
)
```

Add after `BedtimeMessage`:

```kotlin
/** Watch → phone: I'M UP was pressed (`/sleep/wake`). */
@Serializable
data class WakeAnchorMessage(val atEpochMs: Long)
```

Add to the end of `SleepSummary`'s parameter list:

```kotlin
    /** The latest bed-button press and wake anchor (dismiss or I'M UP) the phone knows of; they drive the watch's BED/UP toggle. */
    val lastBedAnchorEpochMs: Long? = null,
    val lastWakeAnchorEpochMs: Long? = null,
```

In `DataLayer.kt` add `const val SLEEP_WAKE = "/sleep/wake"` after `SLEEP_BEDTIME`.

- [ ] **Step 2: Write the failing reducer tests.** Append to `WakeStateMachineTest`:

```kotlin
    /** Gate: #7 wake behaviour — minutes are counted from the first stage, not from a later one. */
    @Test
    fun `the first stage time is set once and kept through later stages`() {
        val light = WakeStateMachine.reduce(scheduled, WakeEvent.StageStarted(StageType.LIGHT), now, profile)
        val sound = WakeStateMachine.reduce(light, WakeEvent.StageStarted(StageType.SOUND), now.plusSeconds(600), profile)
        assertEquals(now.toEpochMilli(), sound.firstStageAtEpochMs)
    }

    /** Gate: #7 wake behaviour — the stage you dismissed in survives the dismiss. */
    @Test
    fun `dismiss keeps the stage that was running`() {
        val firing = scheduled.copy(state = InstanceState.FIRING, currentStage = StageType.SOUND)
        assertEquals(StageType.SOUND, WakeStateMachine.reduce(firing, WakeEvent.Dismiss(Device.PHONE), now, profile).dismissedAtStage)
    }
```

- [ ] **Step 3: Run and see them fail.** Run: `./gradlew :core:wake:test --offline --tests '*WakeStateMachineTest*'`. Expected: FAIL (`firstStageAtEpochMs` is null / `dismissedAtStage` is null).

- [ ] **Step 4: Implement the reducer.** In `WakeStateMachine.reduce`:

```kotlin
            is WakeEvent.StageStarted -> instance.copy(
                state = InstanceState.FIRING,
                currentStage = event.type,
                firstStageAtEpochMs = instance.firstStageAtEpochMs ?: now.toEpochMilli(),
            )
```

and in `dismissed(...)` add `dismissedAtStage = instance.currentStage,` before `currentStage = null`.

- [ ] **Step 5: Run the reducer tests.** Same command. Expected: PASS (all 5).

- [ ] **Step 6: Write the failing `decide`/`current` tests.** Append to `ScheduleBuilderTest` (it already has `zone`, `at`, `daily`, `t`, `instance`, `decide`):

```kotlin
    private val weekdays = daily.copy(repeatDays = NextOccurrence.WEEKDAYS)

    /** Gate: skip spec — a skipped occurrence never rings, and the next repeat day takes over. 2026-10-01 is a Thursday. */
    @Test
    fun `a pending occurrence on the skip date is closed as skipped and the next repeat day becomes active`() {
        val d = decide(weekdays.copy(skipNextDate = "2026-10-01"), instance(InstanceState.SCHEDULED), now = at("2026-09-30T22:00"))
        assertEquals(InstanceState.SKIPPED, d.skipped!!.state)
        assertEquals(at("2026-10-02T06:30").toEpochMilli(), d.active!!.scheduledAtEpochMs)
        assertNull(d.cancelled)
    }

    /** Gate: skip spec — undo. After a Thursday skip, Friday is pending; clearing the skip brings Thursday back. */
    @Test
    fun `clearing a skip reopens the skipped occurrence and cancels the one after it`() {
        val friday = instance(InstanceState.SCHEDULED, at("2026-10-02T06:30"))
        val d = decide(weekdays, friday, now = at("2026-09-30T22:05"))
        assertEquals(InstanceIds.of(1, t), d.active!!.id)
        assertEquals(InstanceState.SCHEDULED, d.active!!.state)
        assertEquals(InstanceState.CANCELLED, d.cancelled!!.state)
        assertEquals(friday.id, d.cancelled!!.id)
    }

    /** Gate: skip spec — SKIP never cuts off a wake-up that is already running. */
    @Test
    fun `a ringing occurrence on the skip date keeps ringing`() {
        val firing = instance(InstanceState.FIRING)
        assertSame(firing, decide(daily.copy(skipNextDate = "2026-10-01"), firing, now = at("2026-10-01T06:25")).active)
    }

    /** Gate: #6 — disarming during the sunrise stops it. */
    @Test
    fun `disarming a ringing channel cancels its occurrence`() {
        val d = decide(daily.copy(enabled = false), instance(InstanceState.FIRING), now = at("2026-10-01T06:25"))
        assertEquals(InstanceState.CANCELLED, d.cancelled!!.state)
        assertNull(d.active)
    }

    /** Gate: #5 — a replaced occurrence is closed, not left open. */
    @Test
    fun `moving the time cancels the replaced occurrence`() {
        val d = decide(daily.copy(hour = 7, minute = 0), instance(InstanceState.SCHEDULED), now = at("2026-09-30T22:00"))
        assertEquals(InstanceState.CANCELLED, d.cancelled!!.state)
    }

    /** Gate: #5 — an edit that keeps the time (profile, tone, target) must not cancel anything. */
    @Test
    fun `changing only the profile keeps the pending occurrence`() {
        val pending = instance(InstanceState.SCHEDULED)
        val d = decide(daily.copy(profileId = WakeProfile.QUICK.id), pending, now = at("2026-09-30T22:00"))
        assertSame(pending, d.active)
        assertNull(d.cancelled)
    }

    /** Gate: current-occurrence rule — moving an alarm earlier the same day still rings today. */
    @Test
    fun `after moving 7 00 to 6 30 the next run keeps 6 30 active`() {
        val seven = instance(InstanceState.SCHEDULED, at("2026-10-01T07:00"))
        val first = decide(daily, seven, now = at("2026-09-30T22:00"))
        val rows = listOf(first.cancelled!!, first.active!!)
        val second = decide(daily, ScheduleBuilder.current(rows), now = at("2026-09-30T22:01"))
        assertEquals(t.toEpochMilli(), second.active!!.scheduledAtEpochMs)
        assertNull(second.cancelled)
    }
```

- [ ] **Step 7: Run and see them fail.** Run: `./gradlew :core:wake:test --offline --tests '*ScheduleBuilderTest*'`. Expected: compile failure (`skipped`, `cancelled`, `current` don't exist).

- [ ] **Step 8: Implement `decide` and `current`.** Replace `ChannelDecision` and `ScheduleBuilder` in `ScheduleBuilder.kt` (keep `InstanceIds` and `Channel` as they are):

```kotlin
/**
 * @param active the occurrence to keep registered (new, unchanged, or still ringing); null = nothing to register.
 * @param missed the previous occurrence, closed as SILENCED because its auto-silence time passed without a dismiss.
 * @param skipped the previous occurrence, closed as SKIPPED because its date is the channel's skip date.
 * @param cancelled the previous occurrence, closed as CANCELLED because the channel was disarmed or its time moved.
 */
data class ChannelDecision(
    val active: AlarmInstance?,
    val missed: AlarmInstance? = null,
    val skipped: AlarmInstance? = null,
    val cancelled: AlarmInstance? = null,
)

/**
 * Rolls one channel forward. The next occurrence is searched after max(now, previous T), so an early
 * dismiss never re-arms the same morning (REVIEW R5). A ringing occurrence is kept unless its channel was
 * disarmed or moved, which cancels it (#6).
 */
object ScheduleBuilder {

    /**
     * The channel's current occurrence: the latest by alarm time, ignoring CANCELLED rows. A cancelled row
     * can lie in the future (7:00 moved to 6:30), and treating it as current would roll past 6:30.
     */
    fun current(rows: List<AlarmInstance>): AlarmInstance? =
        rows.filter { it.state != InstanceState.CANCELLED }.maxByOrNull { it.scheduledAtEpochMs }

    fun decide(channel: Channel, now: Instant, zone: ZoneId): ChannelDecision {
        val (alarm, profile, last) = channel
        var missed: AlarmInstance? = null
        var skipped: AlarmInstance? = null
        var cancelled: AlarmInstance? = null
        var open: AlarmInstance? = null
        var after = now

        if (last != null) {
            val lastT = Instant.ofEpochMilli(last.scheduledAtEpochMs)
            val live = !last.state.isTerminal && now.isBefore(WakePlanner.silenceAt(lastT, profile))
            val overdue = live && !now.isBefore(lastT)
            val unchanged = alarm.enabled && lastT.atZone(zone).toLocalTime() == LocalTime.of(alarm.hour, alarm.minute)
            val onSkipDate = lastT.atZone(zone).toLocalDate().toString() == alarm.skipNextDate
            when {
                live && last.state == InstanceState.FIRING && unchanged -> return ChannelDecision(last)
                live && last.state == InstanceState.FIRING -> {
                    cancelled = last.closedAs(InstanceState.CANCELLED)
                    after = maxOf(now, lastT)
                }
                live && onSkipDate -> {
                    skipped = last.closedAs(InstanceState.SKIPPED)
                    after = lastT
                }
                // Past T but its alarm never arrived (dropped, or a reboot): keep it so it is re-registered and
                // rings now, ramps resumed, instead of silently rolling to tomorrow.
                overdue && unchanged -> return ChannelDecision(last)
                live -> open = last // still ahead, or the channel was edited since: recompute below
                !last.state.isTerminal -> {
                    missed = last.closedAs(InstanceState.SILENCED)
                    after = maxOf(now, lastT)
                }
                else -> after = maxOf(now, lastT)
            }
        }

        val fireAt = NextOccurrence.nextFireTime(alarm, after, zone)
        val id = fireAt?.let { InstanceIds.of(alarm.id, it) }
        if (open != null && open.id == id) return ChannelDecision(open)
        if (open != null) cancelled = open.closedAs(InstanceState.CANCELLED)
        val active = fireAt?.let { AlarmInstance(id!!, alarm.id, it.toEpochMilli()) }
        return ChannelDecision(active, missed, skipped, cancelled)
    }

    private fun AlarmInstance.closedAs(state: InstanceState) = copy(state = state, currentStage = null)
}
```

- [ ] **Step 9: Run all wake tests.** Run: `./gradlew :core:wake:test --offline`. Expected: PASS, including the existing `ScheduleBuilderTest` cases (`editing the time replaces the pending occurrence`, `a disarmed channel registers nothing`, `an overdue occurrence of a channel that was edited since is replaced, not rung` still hold: they now also report `cancelled`, which they don't assert against).

- [ ] **Step 10: Write the failing `ImUp` and `WakeBehaviour` tests.** Create `core/wake/src/test/kotlin/com/trepidity/good/wake/ImUpTest.kt`:

```kotlin
package com.trepidity.good.wake

import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.ScheduleEntry
import com.trepidity.good.model.SoundTarget
import com.trepidity.good.model.WakeProfile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Gate: I'M UP spec — targets are today's open occurrences before 14:00, never tomorrow's, never a CHK test. */
class ImUpTest {
    private val zone = ZoneId.of("America/Chicago")
    private fun at(s: String): Instant = ZonedDateTime.parse("$s-05:00[America/Chicago]").toInstant()
    private fun entry(fireAt: String, channel: Long = 1, state: InstanceState = InstanceState.SCHEDULED) = ScheduleEntry(
        AlarmInstance(InstanceIds.of(channel, at(fireAt)), channel, at(fireAt).toEpochMilli(), state), WakeProfile.GENTLE, SoundTarget.AUTO,
    )

    @Test
    fun `a 05 00 press targets this morning's 06 30`() {
        val today = entry("2026-10-01T06:30")
        assertEquals(listOf(today), ImUp.targets(listOf(today), at("2026-10-01T05:00"), zone))
    }

    @Test
    fun `a 22 00 press never targets tomorrow morning`() {
        assertEquals(emptyList<ScheduleEntry>(), ImUp.targets(listOf(entry("2026-10-01T06:30")), at("2026-09-30T22:00"), zone))
    }

    @Test
    fun `13 59 is a target and 14 00 is not`() {
        val before = entry("2026-10-01T13:59", channel = 1)
        val atCutoff = entry("2026-10-01T14:00", channel = 2)
        assertEquals(listOf(before), ImUp.targets(listOf(before, atCutoff), at("2026-10-01T09:00"), zone))
    }

    @Test
    fun `a ringing occurrence is a target but a closed one and a CHK test alarm are not`() {
        val ringing = entry("2026-10-01T06:30", channel = 1, state = InstanceState.FIRING)
        val closed = entry("2026-10-01T07:00", channel = 2, state = InstanceState.SKIPPED)
        val test = entry("2026-10-01T06:40", channel = 0)
        assertEquals(listOf(ringing), ImUp.targets(listOf(ringing, closed, test), at("2026-10-01T06:25"), zone))
    }
}
```

Create `core/wake/src/test/kotlin/com/trepidity/good/wake/WakeBehaviourTest.kt`:

```kotlin
package com.trepidity.good.wake

import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.Device
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.StageType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Gate: #7 — the wake-behaviour metric, with I'M UP's early dismisses shown apart. */
class WakeBehaviourTest {
    private val t = 1_790_000_000_000L
    private val base = AlarmInstance("a1", 1, t)

    @Test
    fun `a dismiss during the sound stage reports the stage and minutes after the first stage`() {
        val i = base.copy(
            state = InstanceState.DISMISSED, firstStageAtEpochMs = t - 600_000, dismissedAtStage = StageType.SOUND,
            dismissedAtEpochMs = t - 600_000 + 6 * 60_000 + 59_000, dismissedOn = Device.PHONE,
        )
        assertEquals(WakeBehaviour.Dismissed(StageType.SOUND, 6), WakeBehaviour.of(i))
    }

    @Test
    fun `a dismiss before any stage is up early`() {
        val i = base.copy(state = InstanceState.DISMISSED, dismissedAtEpochMs = t - 3_600_000, dismissedOn = Device.WATCH)
        assertEquals(WakeBehaviour.UpEarly, WakeBehaviour.of(i))
    }
}
```

- [ ] **Step 11: Run and see them fail.** Run: `./gradlew :core:wake:test --offline`. Expected: compile failure (`ImUp`, `WakeBehaviour` don't exist).

- [ ] **Step 12: Implement.** Create `core/wake/src/main/kotlin/com/trepidity/good/wake/ImUp.kt`:

```kotlin
package com.trepidity.good.wake

import com.trepidity.good.model.ScheduleEntry
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * I'M UP closes every open occurrence due today before 14:00, ringing or not. The cutoff matches the night
 * window's end, so a press in the evening never cancels tomorrow's alarm. Phone and watch both call this, so an
 * offline watch and the phone agree on what was closed.
 */
object ImUp {
    private val CUTOFF: LocalTime = LocalTime.of(14, 0)

    fun targets(entries: List<ScheduleEntry>, now: Instant, zone: ZoneId): List<ScheduleEntry> {
        val today = now.atZone(zone).toLocalDate()
        return entries.filter { e ->
            val t = Instant.ofEpochMilli(e.instance.scheduledAtEpochMs).atZone(zone)
            e.instance.alarmId > 0 && !e.instance.state.isTerminal && t.toLocalDate() == today && t.toLocalTime() < CUTOFF
        }
    }
}
```

Create `core/wake/src/main/kotlin/com/trepidity/good/wake/WakeBehaviour.kt`:

```kotlin
package com.trepidity.good.wake

import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.StageType

/** How one morning's alarm ended, for the SLP lap (SPEC Sleep tracking → Wake behaviour, #7). */
sealed interface WakeBehaviour {
    /** Dismissed during [stage], [minutesAfterFirstStage] after the first stage started. */
    data class Dismissed(val stage: StageType, val minutesAfterFirstStage: Long) : WakeBehaviour
    /** Closed by I'M UP (or a dismiss) before any stage started. */
    data object UpEarly : WakeBehaviour
    data object Skipped : WakeBehaviour
    data object NoAnswer : WakeBehaviour

    companion object {
        /** Null for an occurrence that is still open or was CANCELLED by an edit. */
        fun of(instance: AlarmInstance): WakeBehaviour? = when (instance.state) {
            InstanceState.DISMISSED -> {
                val first = instance.firstStageAtEpochMs
                val stage = instance.dismissedAtStage
                val at = instance.dismissedAtEpochMs
                if (first == null || stage == null || at == null) UpEarly else Dismissed(stage, (at - first) / 60_000)
            }
            InstanceState.SKIPPED -> Skipped
            InstanceState.SILENCED -> NoAnswer
            InstanceState.SCHEDULED, InstanceState.FIRING, InstanceState.CANCELLED -> null
        }
    }
}
```

- [ ] **Step 13: Run all core tests.** Run: `./gradlew :core:wake:test :core:sync:test :core:sleep:test --offline`. Expected: PASS.

- [ ] **Step 14: Check the apps still compile.** The new `ChannelDecision` fields have defaults and `Command`/`AlarmInstance` gained defaulted params, so the apps should compile unchanged. `InstanceState` gained a value: search for exhaustive `when` over it (`grep -rn "InstanceState\." app-phone/src app-wear/src`) and fix any that no longer compile. Run the build + lint command from Global Constraints. Expected: BUILD SUCCESSFUL.

- [ ] **Step 15: Commit.**

```bash
git add core app-phone app-wear
git commit -m "Core: SKIPPED/CANCELLED decisions, current occurrence, I'M UP targets, wake behaviour

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Core sleep toggle and inferred wake

**Files:**
- Create: `core/sleep/src/main/kotlin/com/trepidity/good/sleep/SleepToggle.kt`
- Create: `core/sleep/src/main/kotlin/com/trepidity/good/sleep/WakeInference.kt`
- Test: create `core/sleep/src/test/kotlin/com/trepidity/good/sleep/SleepToggleTest.kt`, `WakeInferenceTest.kt` (use the existing `at()` and `CHICAGO` from `Fixtures.kt`)

**Interfaces:**
- Consumes: `NightWindow.wakeDateOf(at, zone)`, `NightWindow.forWakeDate(date, zone)`, `NightWindow.contains`.
- Produces:
  - `enum class SleepAction { BED, UP }`
  - `SleepToggle.next(lastBed: Instant?, lastWake: Instant?, now: Instant, zone: ZoneId): SleepAction`
  - `WakeInference.infer(skippedAt: Instant, candidates: List<Instant>, nightStart: Instant, window: NightWindow): Instant?`

- [ ] **Step 1: Write the failing tests.** `SleepToggleTest.kt`:

```kotlin
package com.trepidity.good.sleep

import org.junit.Assert.assertEquals
import org.junit.Test

/** Gate: I'M UP spec — the BED/UP toggle is UP only after tonight's bed press, and never gets stuck on UP. */
class SleepToggleTest {
    private fun next(bed: String?, wake: String?, now: String) =
        SleepToggle.next(bed?.let(::at), wake?.let(::at), at(now), CHICAGO)

    @Test fun `a bed press tonight with no wake since shows UP`() =
        assertEquals(SleepAction.UP, next("2026-09-30T23:00", null, "2026-10-01T05:00"))

    @Test fun `a wake after the bed press shows BED`() =
        assertEquals(SleepAction.BED, next("2026-09-30T23:00", "2026-10-01T06:00", "2026-10-01T06:01"))

    @Test fun `last night's bed press without a wake does not carry into tonight`() =
        assertEquals(SleepAction.BED, next("2026-09-29T23:00", null, "2026-09-30T22:00"))

    @Test fun `between 14 00 and 18 00 it is always BED`() =
        assertEquals(SleepAction.BED, next("2026-10-01T13:30", null, "2026-10-01T15:00"))
}
```

`WakeInferenceTest.kt`:

```kotlin
package com.trepidity.good.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Gate: #1 — a skipped night's wake is the first unlock or watch "awake" after the skipped alarm, within 24 h. */
class WakeInferenceTest {
    private val window = NightWindow.forWakeDate(LocalDate.parse("2026-10-01"), CHICAGO)
    private val skipped = at("2026-10-01T06:30")
    private val bed = at("2026-09-30T23:00")

    @Test
    fun `candidates before the skipped alarm time are ignored and the earliest after it wins`() {
        val c = listOf(at("2026-10-01T03:10"), at("2026-10-01T09:40"), at("2026-10-01T09:10"))
        assertEquals(at("2026-10-01T09:10"), WakeInference.infer(skipped, c, bed, window))
    }

    @Test
    fun `a candidate outside the night window is not a wake`() {
        assertNull(WakeInference.infer(skipped, listOf(at("2026-10-01T15:00")), bed, window))
    }

    @Test
    fun `a candidate 24 h or more after the night start is rejected`() {
        val longWindow = NightWindow(window.wakeDate, window.start, at("2026-10-02T12:00"))
        assertNull(WakeInference.infer(skipped, listOf(at("2026-10-01T23:00")), bed, longWindow))
    }
}
```

- [ ] **Step 2: Run and see them fail.** Run: `./gradlew :core:sleep:test --offline`. Expected: compile failure.

- [ ] **Step 3: Implement.** `SleepToggle.kt`:

```kotlin
package com.trepidity.good.sleep

import java.time.Instant
import java.time.ZoneId

enum class SleepAction { BED, UP }

/**
 * The bed button is a start/stop toggle: after tonight's GOOD NIGHT it becomes GOOD MORNING (I'M UP). Only a bed
 * press inside the current night window counts, so a press from a previous night never leaves it stuck on UP.
 */
object SleepToggle {
    fun next(lastBed: Instant?, lastWake: Instant?, now: Instant, zone: ZoneId): SleepAction {
        val date = NightWindow.wakeDateOf(now, zone) ?: return SleepAction.BED
        val bed = lastBed?.takeIf { it in NightWindow.forWakeDate(date, zone) && !it.isAfter(now) } ?: return SleepAction.BED
        return if (lastWake != null && !lastWake.isBefore(bed)) SleepAction.BED else SleepAction.UP
    }
}
```

`WakeInference.kt`:

```kotlin
package com.trepidity.good.sleep

import java.time.Duration
import java.time.Instant

/**
 * A skipped night has no dismiss to end it. Its wake is the first sign of being up (a phone unlock or a watch
 * "awake" state) at or after the skipped alarm time, inside the night window and under 24 h after the night began
 * (#1). Candidates before the alarm time are ignored: a 3 a.m. unlock is not getting up.
 */
object WakeInference {
    private val MAX_NIGHT: Duration = Duration.ofHours(24)

    fun infer(skippedAt: Instant, candidates: List<Instant>, nightStart: Instant, window: NightWindow): Instant? =
        candidates
            .filter { !it.isBefore(skippedAt) && it in window && Duration.between(nightStart, it) < MAX_NIGHT }
            .minOrNull()
}
```

- [ ] **Step 4: Run the tests.** Run: `./gradlew :core:sleep:test --offline`. Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add core/sleep
git commit -m "Core sleep: BED/UP toggle and inferred wake for skipped nights

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Phone data and scheduling — skip, cancel, close

**Files:**
- Modify: `app-phone/src/main/kotlin/com/trepidity/good/phone/data/Entities.kt` (`InstanceEntity`)
- Modify: `app-phone/src/main/kotlin/com/trepidity/good/phone/data/Daos.kt` (`InstanceDao`)
- Modify: `app-phone/src/main/kotlin/com/trepidity/good/phone/data/GoodDatabase.kt`
- Create (generated by the build, then committed): `app-phone/schemas/com.trepidity.good.phone.data.GoodDatabase/2.json`
- Modify: `app-phone/src/main/kotlin/com/trepidity/good/phone/alarm/AlarmRepository.kt`
- Modify: `app-phone/src/main/kotlin/com/trepidity/good/phone/alarm/InstanceEvents.kt`
- Modify: `app-phone/src/main/kotlin/com/trepidity/good/phone/sync/PhoneListenerService.kt`
- Modify: `app-phone/src/main/kotlin/com/trepidity/good/phone/DataExport.kt`

**Interfaces:**
- Consumes (Task 1): `ChannelDecision.skipped/cancelled`, `ScheduleBuilder.current`, `InstanceState.CANCELLED`, `Command.state`, the two new `AlarmInstance` fields.
- Produces:
  - `InstanceDao.recent(alarmId: Long, limit: Int = 16): List<InstanceEntity>` and `InstanceDao.between(from: Long, to: Long): List<InstanceEntity>`.
  - `AlarmRepository.setSkip(channel: Long, date: String?)`.
  - `InstanceEvents.record(context: Context, instance: AlarmInstance, stampWake: Boolean = true)`.

No unit tests in this task: the decisions it wires are tested in Task 1, and this task is I/O. It's verified by the build and by the device checks in Task 7.

- [ ] **Step 1: Room v2.** In `InstanceEntity` add two nullable columns at the end and map them:

```kotlin
    val dismissedOn: String?,
    val firstStageAt: Long? = null,
    val dismissedAtStage: String? = null,
) {
    fun toModel() = AlarmInstance(
        id, alarmId, scheduledAt, InstanceState.valueOf(state),
        currentStage?.let(StageType::valueOf), dismissedAt, dismissedOn?.let(Device::valueOf),
        firstStageAt, dismissedAtStage?.let(StageType::valueOf),
    )

    companion object {
        fun of(i: AlarmInstance) = InstanceEntity(
            i.id, i.alarmId, i.scheduledAtEpochMs, i.state.name, i.currentStage?.name, i.dismissedAtEpochMs, i.dismissedOn?.name,
            i.firstStageAtEpochMs, i.dismissedAtStage?.name,
        )
    }
```

In `GoodDatabase`: `version = 2`, `autoMigrations = [AutoMigration(from = 1, to = 2)]` (import `androidx.room.AutoMigration`). Adding nullable columns needs no spec class.

In `InstanceDao` replace `latest` with:

```kotlin
    /** The channel's most recent rows by alarm time; [ScheduleBuilder.current] picks the current one from them. */
    @Query("SELECT * FROM alarm_instance WHERE alarmId = :alarmId ORDER BY scheduledAt DESC LIMIT :limit")
    suspend fun recent(alarmId: Long, limit: Int = 16): List<InstanceEntity>
    @Query("SELECT * FROM alarm_instance WHERE scheduledAt BETWEEN :from AND :to ORDER BY scheduledAt")
    suspend fun between(from: Long, to: Long): List<InstanceEntity>
```

Update every caller of `latest` (only `AlarmRepository.rescheduleAll`; check with `grep -rn "latest(" app-phone/src`).

- [ ] **Step 2: Build to generate the v2 schema.** Run: `./gradlew :app-phone:kspDebugKotlin --offline`. Expected: `app-phone/schemas/com.trepidity.good.phone.data.GoodDatabase/2.json` exists and contains `firstStageAt` and `dismissedAtStage`.

- [ ] **Step 3: `rescheduleAll` uses `current`, persists skipped and cancelled, stops a cancelled ringing occurrence.** In `AlarmRepository.rescheduleAll`, replace the line `val last = db.instances().latest(alarm.id)?.toModel()` and the `decision.missed?.let { … }` block with:

```kotlin
            val last = ScheduleBuilder.current(db.instances().recent(alarm.id).map { it.toModel() })
            val decision = ScheduleBuilder.decide(Channel(alarm, profile, last), now, zone)
            decision.missed?.let {
                markInstance(it)
                EventLog.log(context, "MISSED", "${it.id} never dismissed; closed as silenced")
            }
            decision.skipped?.let {
                markInstance(it)
                EventLog.log(context, "SKIPPED", it.id)
            }
            decision.cancelled?.let {
                markInstance(it)
                EventLog.log(context, "CANCELLED", "${it.id} (was ${last?.state})")
                if (last?.state == InstanceState.FIRING) closeRunning(it, now)
            }
```

(The existing `if (active.id != last?.id) db.instances().upsert(InstanceEntity.of(active))` stays; it's what reuses a cancelled or skipped row's ID when an undo or re-arm lands on it again.)

Add to `AlarmRepository`:

```kotlin
    /** A ringing occurrence whose channel was disarmed or moved (#6): stop it here and on the watch. */
    private fun closeRunning(instance: AlarmInstance, now: Instant) {
        ScheduleStore.update(context, instance)
        val cmd = Command(instance.id, now.toEpochMilli(), Device.PHONE, InstanceState.CANCELLED)
        WakeService.remoteCommands.tryEmit(cmd)
        (context.applicationContext as GoodApplication).appScope.launch {
            runCatching { PhoneSync.sendDismiss(context, instance, cmd) }
                .onFailure { EventLog.log(context, "PUSH_FAILED", "close ${instance.id}: ${it.message}") }
        }
    }

    /** SKIP (hold ▼): skip the occurrence on [date] (ISO local date), or clear a pending skip with null. */
    suspend fun setSkip(channel: Long, date: String?) {
        val a = db.alarms().get(channel)?.toModel() ?: return
        db.alarms().upsert(AlarmEntity.of(a.copy(skipNextDate = date)))
        EventLog.log(context, if (date != null) "SKIP" else "UNSKIP", "AL$channel ${date ?: a.skipNextDate}")
        rescheduleAll(if (date != null) "skip" else "unskip")
    }
```

Imports to add: `com.trepidity.good.model.Command`, `com.trepidity.good.model.Device`, `com.trepidity.good.phone.GoodApplication`, `com.trepidity.good.phone.wake.WakeService`, `kotlinx.coroutines.launch`.

- [ ] **Step 4: One-shot channels disarm only when something happened.** In `markInstance` replace `if (instance.state.isTerminal && instance.alarmId > 0)` with:

```kotlin
        // Only an occurrence that rang (or was dismissed) uses up a one-shot. SKIPPED and CANCELLED don't:
        // editing a one-shot's time cancels its old occurrence and must leave the channel armed.
        if ((instance.state == InstanceState.DISMISSED || instance.state == InstanceState.SILENCED) && instance.alarmId > 0) {
```

and update the KDoc's first sentence to "The first DISMISSED or SILENCED transition disarms a one-shot alarm (REVIEW R5)".

- [ ] **Step 5: `InstanceEvents.record` can skip the wake stamp.** Change the signature to `fun record(context: Context, instance: AlarmInstance, stampWake: Boolean = true)` and the condition to `if (stampWake && instance.state == InstanceState.DISMISSED && instance.alarmId > 0)`. KDoc: "[stampWake] false when the caller already recorded the wake time (I'M UP records it once for all its targets)."

- [ ] **Step 6: The phone applies the command's state.** In `PhoneListenerService.remoteDismiss`, replace the `InstanceEvents.record(...)` argument with:

```kotlin
        val closed = if (cmd.state == InstanceState.DISMISSED) {
            entry.instance.copy(
                state = InstanceState.DISMISSED, currentStage = null, dismissedAtStage = entry.instance.currentStage,
                dismissedAtEpochMs = cmd.sentAtEpochMs, dismissedOn = cmd.from,
            )
        } else {
            entry.instance.copy(state = cmd.state, currentStage = null)
        }
        InstanceEvents.record(this, closed)
```

Change the log line to `"DISMISS_REMOTE"` → keep the name, add `as ${cmd.state}` to its detail. Rename the function to `remoteClose` and update its two callers.

- [ ] **Step 7: Export.** In `DataExport.write`, add `"firstStageAt" to it.firstStageAt?.toString(), "dismissedAtStage" to it.dismissedAtStage` to the instance map.

- [ ] **Step 8: Build and lint.** Run the build + lint command. Expected: BUILD SUCCESSFUL, no new lint errors.

- [ ] **Step 9: Commit** (include the generated `2.json`).

```bash
git add app-phone
git commit -m "Phone: skip and cancel occurrences, stop a disarmed wake-up, Room v2

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Phone sleep — I'M UP flow, anchors, inferred wake, daily sync

**Files:**
- Create: `app-phone/src/main/kotlin/com/trepidity/good/phone/alarm/WakeUp.kt`
- Create: `app-phone/src/main/kotlin/com/trepidity/good/phone/sleep/UnlockLog.kt`
- Modify: `app-phone/src/main/kotlin/com/trepidity/good/phone/sleep/SleepRepository.kt`
- Modify: `app-phone/src/main/kotlin/com/trepidity/good/phone/sleep/SleepSyncWorker.kt`
- Modify: `app-phone/src/main/kotlin/com/trepidity/good/phone/data/Daos.kt` (`SleepDao`)
- Modify: `app-phone/src/main/kotlin/com/trepidity/good/phone/sync/PhoneListenerService.kt`
- Modify: `app-phone/src/main/kotlin/com/trepidity/good/phone/ReliabilityCheck.kt`
- Modify: `app-phone/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `ImUp.targets`, `WakeStateMachine.reduce`, `WakeEvent.Dismiss` (Task 1); `SleepToggle.next`, `SleepAction`, `WakeInference.infer` (Task 2); `InstanceEvents.record(…, stampWake)`, `InstanceDao.between` (Task 3); `WakeAnchorMessage`, `DataLayerPaths.SLEEP_WAKE`, `SleepSummary` anchor fields (Task 1).
- Produces:
  - `WakeUp.record(context: Context, at: Instant): Int` (number of alarms closed).
  - `SleepRepository.nextAction(now: Instant = Instant.now()): SleepAction`.
  - `SleepSyncWorker.scheduleDaily(context: Context)`.
  - `UnlockLog.granted(context: Context): Boolean`, `UnlockLog.unlocks(context: Context, from: Long, to: Long): List<Instant>`.
  - `CheckId.USE`.

No unit tests: the rules are Task 2's. Verified by build and UAT 15–17.

- [ ] **Step 1: DAO.** In `SleepDao` add:

```kotlin
    @Query("SELECT * FROM sleep_anchor WHERE kind = :kind ORDER BY at DESC LIMIT 1") suspend fun latestAnchor(kind: String): AnchorEntity?
```

- [ ] **Step 2: `UnlockLog`.** Create `UnlockLog.kt`:

```kotlin
package com.trepidity.good.phone.sleep

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.time.Instant

/**
 * Phone unlocks (keyguard dismissed), read after the fact from usage stats, so no receiver has to be running
 * overnight. Needs Usage access (CHK → USE); without it there are no unlocks and only watch "awake" samples count (#1).
 */
object UnlockLog {
    fun granted(context: Context): Boolean =
        context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED

    fun unlocks(context: Context, from: Long, to: Long): List<Instant> {
        if (!granted(context)) return emptyList()
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(from, to) ?: return emptyList()
        val out = mutableListOf<Instant>()
        val e = UsageEvents.Event()
        while (events.getNextEvent(e)) if (e.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) out += Instant.ofEpochMilli(e.timeStamp)
        return out
    }
}
```

Manifest: add `<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />` under the sleep-data permissions, with the comment `<!-- Unlock times, to end a skipped night (usage access, granted in Settings) -->`.

- [ ] **Step 3: `SleepRepository`.**
  - `recordBedtime`: after the log line, call `publishSummary()`.
  - `onWake`: after scheduling the syncs, call `SleepSyncWorker.scheduleDaily(context)` and `publishSummary()`.
  - Add:

```kotlin
    /** GOOD NIGHT or GOOD MORNING next, from the latest bed and wake anchors (I'M UP spec). */
    suspend fun nextAction(now: Instant = Instant.now()): SleepAction {
        val (bed, wake) = lastAnchors()
        return SleepToggle.next(bed, wake, now, zone)
    }

    private suspend fun lastAnchors(): Pair<Instant?, Instant?> =
        dao.latestAnchor(AnchorEntity.BED)?.at?.let(Instant::ofEpochMilli) to dao.latestAnchor(AnchorEntity.WAKE)?.at?.let(Instant::ofEpochMilli)
```

  - In `rebuild`, replace the `val anchors = Anchors(...)` line with:

```kotlin
        val wake = dismiss?.let(Instant::ofEpochMilli) ?: inferWake(window, bed?.let(Instant::ofEpochMilli), samples)
        val anchors = Anchors(bed?.let(Instant::ofEpochMilli), wake)
```

  and add:

```kotlin
    /**
     * A night whose alarm was skipped has no dismiss. Its wake is inferred from the first phone unlock or watch
     * "awake" after the skipped time (#1). Not stored: a Health Connect session or a real I'M UP still wins later.
     */
    private suspend fun inferWake(window: NightWindow, bed: Instant?, samples: List<Sample>): Instant? {
        val from = window.start.toEpochMilli()
        val to = window.end.toEpochMilli()
        val skipped = db.instances().between(from, to)
            .firstOrNull { it.alarmId > 0 && it.state == InstanceState.SKIPPED.name } ?: return null
        val skippedAt = Instant.ofEpochMilli(skipped.scheduledAt)
        val unlocks = UnlockLog.unlocks(context, from, to)
        val awake = samples.filter { it.source == SleepSource.WATCH && !it.asleep }.map { it.at }
        val inferred = WakeInference.infer(skippedAt, unlocks + awake, bed ?: skippedAt, window) ?: return null
        val via = if (inferred in unlocks) "unlock" else "watch awake"
        EventLog.log(context, "WAKE_INFERRED", "${window.wakeDate} at $inferred via $via")
        return inferred
    }
```

  `WAKE_INFERRED` may log on every rebuild of that night (a few per day); that's acceptable for a 30-day pruned log.
  - `publishSummary`: read `val (bed, wake) = lastAnchors()` and pass `lastBedAnchorEpochMs = bed?.toEpochMilli(), lastWakeAnchorEpochMs = wake?.toEpochMilli()`.

- [ ] **Step 4: Daily sync.** In `SleepSyncWorker.doWork`, when there's no date input, run the daily pass instead of returning:

```kotlin
    override suspend fun doWork(): Result {
        val repo = AppGraph.sleep(applicationContext)
        val date = inputData.getString(KEY_DATE)?.let(LocalDate::parse)
        if (date == null) repo.syncRecent(nights = 2) else repo.rebuild(date)
        return Result.success()
    }
```

Add to the companion:

```kotlin
        /**
         * A daily pass around 11:00 over the last two nights, so a night with no dismiss (skipped, or no alarm)
         * still picks up Health Connect data and an inferred wake without the app being opened.
         */
        fun scheduleDaily(context: Context) {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            var next = now.toLocalDate().atTime(11, 0).atZone(zone)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val req = PeriodicWorkRequestBuilder<SleepSyncWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sleep-daily", ExistingPeriodicWorkPolicy.KEEP, req)
        }
```

Update the class KDoc: "…at dismiss +30 min, +2 h and at 11:00 (SPEC Sleep tracking step 3), plus a daily 11:00 pass."

- [ ] **Step 5: `WakeUp` (phone I'M UP).** Create `app-phone/src/main/kotlin/com/trepidity/good/phone/alarm/WakeUp.kt`:

```kotlin
package com.trepidity.good.phone.alarm

import android.content.Context
import com.trepidity.good.model.Command
import com.trepidity.good.model.Device
import com.trepidity.good.phone.AppGraph
import com.trepidity.good.phone.EventLog
import com.trepidity.good.phone.sync.PhoneSync
import com.trepidity.good.phone.wake.WakeService
import com.trepidity.good.wake.ImUp
import com.trepidity.good.wake.WakeEvent
import com.trepidity.good.wake.WakeStateMachine
import java.time.Instant
import java.time.ZoneId

/**
 * I'M UP on the phone: stamp the wake time once, then close every alarm still due this morning as dismissed
 * before ringing, through the same path as a dismiss, so the watch stops too (I'M UP spec).
 */
object WakeUp {
    suspend fun record(context: Context, at: Instant): Int {
        AppGraph.sleep(context).onWake(at)
        val entries = ScheduleStore.load(context)?.entries.orEmpty()
        val targets = ImUp.targets(entries, at, ZoneId.systemDefault())
        for (e in targets) {
            val closed = WakeStateMachine.reduce(e.instance, WakeEvent.Dismiss(Device.PHONE), at, e.profile)
            val cmd = Command(closed.id, at.toEpochMilli(), Device.PHONE)
            WakeService.remoteCommands.tryEmit(cmd)
            InstanceEvents.record(context, closed, stampWake = false)
            runCatching { PhoneSync.sendDismiss(context, closed, cmd) }
        }
        EventLog.log(context, "IM_UP", "${targets.size} alarm(s) closed")
        AppGraph.alarms(context).rescheduleAll("i'm up")
        return targets.size
    }
}
```

- [ ] **Step 6: `/sleep/wake` from the watch.** In `PhoneListenerService.onMessageReceived` add:

```kotlin
            DataLayerPaths.SLEEP_WAKE -> runCatching { SyncCodec.decodeAny<WakeAnchorMessage>(event.data) }.getOrNull()?.let { msg ->
                background { AppGraph.sleep(this).onWake(Instant.ofEpochMilli(msg.atEpochMs)) }
            }
```

The watch sends its target dismisses separately (existing `/cmd/dismiss`), so this handler only stamps the wake.

- [ ] **Step 7: CHK `USE`.** Add `USE("USE", "Usage access")` to the end of `CheckId`, and as the last item of `ReliabilityCheck.run`:

```kotlin
            CheckItem(CheckId.USE, UnlockLog.granted(context), "unlock times end a skipped night",
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri)),
```

`InstrumentModel.chkSet` already opens `item.fix` for any other item, so no UI change is needed. If `ACTION_USAGE_ACCESS_SETTINGS` with a package URI fails to resolve on the device, drop the URI (it opens the list).

- [ ] **Step 8: Build and lint.** Run the build + lint command. Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit.**

```bash
git add app-phone
git commit -m "Phone: I'M UP flow, inferred wake for skipped nights, daily sleep pass, CHK USE

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Phone UI — hold ▼ to skip, SLP toggle, wake-behaviour line

**Files:**
- Modify: `app-phone/src/main/kotlin/com/trepidity/good/phone/ui/InstrumentModel.kt`
- Modify: `app-phone/src/main/kotlin/com/trepidity/good/phone/ui/Instrument.kt`

**Interfaces:**
- Consumes: `AlarmRepository.setSkip` (Task 3); `WakeUp.record`, `SleepRepository.nextAction` (Task 4); `SleepAction` (Task 2); `WakeBehaviour` (Task 1); `InstanceDao.since` (existing), `NightWindow.wakeDateOf` (existing).
- Produces: `InstrumentModel.holdDown()`, `UiState.sleepAction: SleepAction`, `InstrumentModel.wakeLines: StateFlow<Map<String, String>>` (wake date → LCD line).

No unit tests: presentation only, over rules tested in Tasks 1–2. Verified by UAT 13–15 and 19.

- [ ] **Step 1: SKIP in the model.** In `InstrumentModel` add:

```kotlin
    /** ALM, hold ▼ 2 s: skip the shown channel's next occurrence, or clear a pending skip (skip spec). */
    fun holdDown() {
        val s = _state.value
        if (s.mode != Mode.ALM || s.almEdit != null) return
        val a = alarm(s.channel)
        val today = LocalDate.now()
        val pending = a.skipNextDate?.let(LocalDate::parse)?.takeIf { !it.isBefore(today) }
        val entry = entryFor(s.channel)
        val date = entry?.let { Instant.ofEpochMilli(it.instance.scheduledAtEpochMs).atZone(ZoneId.systemDefault()).toLocalDate() }
        when {
            pending != null -> skip(s.channel, null, "UNSKIP")
            a.repeatDays == 0 -> banner("ONCE USE OFF")
            !a.enabled || entry == null || date == null -> banner("OFF")
            entry.instance.state != InstanceState.SCHEDULED -> banner("RINGING")
            else -> skip(s.channel, date.toString(), "SKIPPED")
        }
    }

    private fun skip(channel: Long, date: String?, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            alarmsRepo.setSkip(channel, date)
            refreshSnapshot()
            banner(text)
        }
    }

    /** The pending skip date of [a], or null; an old date is ignored (NextOccurrence only compares future dates). */
    fun pendingSkip(a: Alarm, today: LocalDate = LocalDate.now()): LocalDate? =
        a.skipNextDate?.let(LocalDate::parse)?.takeIf { !it.isBefore(today) }
```

Use `pendingSkip` inside `holdDown` too instead of the inline expression. Import `com.trepidity.good.model.InstanceState`.

- [ ] **Step 2: ALM status line.** In `Instrument.kt` `AlmFace`, replace the `field == null -> Line(...)` branch with:

```kotlin
        field == null -> {
            val skip = model.pendingSkip(a)
            val status = when {
                !a.enabled -> "OFF"
                skip != null -> "SKIP ${skip.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US).uppercase()}"
                else -> null
            }
            Line(
                listOfNotNull(status, if (a.enabled) model.countdown(entry, now) ?: "ARMED" else null, if (skip == null) profileName else null)
                    .joinToString(" · "),
                palette, 20.dp,
            )
        }
```

Result: `IN 5:59 · GENTLE` normally, `SKIP THU · IN 23:59` with a skip pending, `OFF` when disarmed. Imports: `java.time.format.TextStyle`, `java.util.Locale`.

- [ ] **Step 3: ▼ button.** In `Instrument.kt` replace the ▼ `CaseButton` with:

```kotlin
                val skipHold = s.mode == Mode.ALM && s.almEdit == null
                CaseButton(
                    "▼", palette, onClick = model::down, modifier = Modifier.weight(1f),
                    onLongClick = if (skipHold) { { model.holdDown(); holdProgress = 0f } } else null,
                    onHoldProgress = if (skipHold) { { holdProgress = it } } else null,
                    repeatOnHold = !skipHold,
                    contentDescription = if (skipHold) "Down. Hold two seconds: skip or unskip the next alarm" else "Down",
                )
```

(`CaseButton` gives `onLongClick` priority over `repeatOnHold`, and a quick release is still a click, so a tap still changes channel.)

- [ ] **Step 4: SLP toggle.** Add `val sleepAction: SleepAction = SleepAction.BED` to `UiState`. Add to `InstrumentModel`:

```kotlin
    private fun refreshSleepAction() {
        viewModelScope.launch(Dispatchers.IO) { _state.update { it.copy(sleepAction = sleepRepo.nextAction()) } }
    }
```

Call it at the end of `init` and in `onResume()`. Replace the `edit == null` branch of `slpSet` with:

```kotlin
        if (edit == null) {
            val now = Instant.now()
            viewModelScope.launch(Dispatchers.IO) {
                if (sleepRepo.nextAction(now) == SleepAction.UP) {
                    WakeUp.record(ctx, now)
                    refreshSnapshot()
                    banner("GOOD MORNING")
                } else {
                    prefs.lastBedtime = now.toEpochMilli()
                    sleepRepo.recordBedtime(now, "PHONE")
                    banner("GOOD NIGHT")
                }
                _state.update { it.copy(sleepAction = sleepRepo.nextAction()) }
            }
            return
        }
```

In `Instrument.kt` `holdMeaning` is unchanged; the SET `contentDescription` becomes `"Set${if (s.mode == Mode.SLP && s.slpEdit == null) if (s.sleepAction == SleepAction.UP) ": log wake-up now" else ": log bedtime now" else ""}. Hold two seconds: ${holdMeaning(s)}"`.

- [ ] **Step 5: Wake-behaviour line.** In `InstrumentModel` add:

```kotlin
    /** Wake date (ISO) → the SLP lap's wake line ("WOKE SND +6", "UP EARLY", …) for the last 31 nights (#7). */
    val wakeLines = MutableStateFlow<Map<String, String>>(emptyMap())

    private suspend fun loadWakeLines() {
        val zone = ZoneId.systemDefault()
        val since = Instant.now().minus(31, ChronoUnit.DAYS).toEpochMilli()
        wakeLines.value = AppGraph.db(ctx).instances().since(since).map { it.toModel() }
            .filter { it.alarmId > 0 }
            .groupBy { NightWindow.wakeDateOf(Instant.ofEpochMilli(it.scheduledAtEpochMs), zone)?.toString() }
            .mapNotNull { (date, list) ->
                val line = list.sortedBy { it.scheduledAtEpochMs }.firstNotNullOfOrNull { WakeBehaviour.of(it) }?.let(::wakeLine)
                if (date == null || line == null) null else date to line
            }.toMap()
    }

    private fun wakeLine(b: WakeBehaviour): String = when (b) {
        is WakeBehaviour.Dismissed -> "WOKE ${stageCode(b.stage)} +${b.minutesAfterFirstStage}"
        WakeBehaviour.UpEarly -> "UP EARLY"
        WakeBehaviour.Skipped -> "SKIPPED"
        WakeBehaviour.NoAnswer -> "NO ANSWER"
    }

    private fun stageCode(t: StageType) = when (t) {
        StageType.LIGHT -> "LIT"
        StageType.HAPTIC -> "BUZ"
        StageType.SOUND -> "SND"
        StageType.ESCALATE -> "MAX"
    }
```

Call `loadWakeLines()` inside the existing `sessions.collect { … }` block in `init` (after `nights.value = …`). If `AppGraph.db` isn't the accessor name, check `AppGraph.kt`.

In `SlpFace`, after the `Line("BED $bed  UP $up", …)` line (inside the `edit == null` branch):

```kotlin
        val wakeLines by model.wakeLines.collectAsState()
        wakeLines[wakeDate.toString()]?.let { Line(it, palette, 16.dp) }
```

(Hoist the `collectAsState` to the top of `SlpFace` with the other two.)

- [ ] **Step 6: Build and lint.** Run the build + lint command. Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Emulator smoke check (if the `GOOD_Phone` AVD is available).** Per `~/.claude/projects/-Users-jared-Projects-GOOD/memory/good-emulator-testing.md`: install the debug APK, open GOOD, wait ~9 s, then via `uiautomator dump` find "Down. Hold two seconds…" and long-press it for 2.5 s (`adb shell input swipe x y x y 2500`) on an armed weekday channel. Expected: banner `SKIPPED`, then `SKIP <DAY> · IN …`. Tap ▼ once: the channel changes. Record what you saw in the task report. If no emulator is available, say so in the report.

- [ ] **Step 8: Commit.**

```bash
git add app-phone
git commit -m "Phone UI: hold ▼ to skip, GOOD NIGHT/GOOD MORNING toggle, wake-behaviour line

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Watch — close state, BED/UP toggle, I'M UP offline

**Files:**
- Modify: `app-wear/build.gradle.kts` (+ `implementation(project(":core:sleep"))`)
- Modify: `app-wear/src/main/kotlin/com/trepidity/good/wear/sync/WatchListenerService.kt`
- Modify: `app-wear/src/main/kotlin/com/trepidity/good/wear/alarm/WatchScheduleStore.kt`
- Create: `app-wear/src/main/kotlin/com/trepidity/good/wear/sleep/WatchImUp.kt`
- Modify: `app-wear/src/main/kotlin/com/trepidity/good/wear/ui/WatchApp.kt`
- Modify: `app-wear/src/main/kotlin/com/trepidity/good/wear/surface/GoodTileService.kt`

**Interfaces:**
- Consumes: `Command.state`, `WakeAnchorMessage`, `DataLayerPaths.SLEEP_WAKE`, `ImUp.targets`, `WakeStateMachine`, `SleepSummary` anchor fields (Task 1); `SleepToggle.next`, `SleepAction` (Task 2).
- Produces: `WatchScheduleStore.recordLocalBed(context, at: Long)`, `recordLocalWake(context, at: Long)`, `sleepAction(context, now: Instant = Instant.now()): SleepAction`; `WatchImUp.record(context: Context, at: Instant): Int`.

No unit tests (rules are in Tasks 1–2). Verified by build and UAT 15–16.

- [ ] **Step 1: Dependency.** Add `implementation(project(":core:sleep"))` next to `:core:wake` in `app-wear/build.gradle.kts`.

- [ ] **Step 2: The watch applies the command's state.** In `WatchListenerService.remoteDismiss` (rename to `remoteClose`, update both callers), replace the `WatchScheduleStore.update(...)` argument with:

```kotlin
        val closed = if (cmd.state == InstanceState.DISMISSED) {
            entry.instance.copy(
                state = InstanceState.DISMISSED, currentStage = null, dismissedAtStage = entry.instance.currentStage,
                dismissedAtEpochMs = cmd.sentAtEpochMs, dismissedOn = cmd.from,
            )
        } else {
            entry.instance.copy(state = cmd.state, currentStage = null)
        }
        WatchScheduleStore.update(this, closed)
```

- [ ] **Step 3: Local anchors and the toggle.** In `WatchScheduleStore` add:

```kotlin
    private const val KEY_BED = "localBedAt"
    private const val KEY_WAKE = "localWakeAt"

    /** Presses made on the watch, so the BED/UP toggle flips at once even with the phone out of range. */
    fun recordLocalBed(context: Context, at: Long) = prefs(context).edit(commit = true) { putLong(KEY_BED, at) }
    fun recordLocalWake(context: Context, at: Long) = prefs(context).edit(commit = true) { putLong(KEY_WAKE, at) }

    /** BED or UP: the later of the phone's and the watch's own anchors of each kind (I'M UP spec). */
    fun sleepAction(context: Context, now: Instant = Instant.now()): SleepAction {
        val p = prefs(context)
        val summary = sleepSummary.value ?: summary(context)
        val bed = listOfNotNull(summary?.lastBedAnchorEpochMs, p.getLong(KEY_BED, 0).takeIf { it > 0 }).maxOrNull()
        val wake = listOfNotNull(summary?.lastWakeAnchorEpochMs, p.getLong(KEY_WAKE, 0).takeIf { it > 0 }).maxOrNull()
        return SleepToggle.next(bed?.let(Instant::ofEpochMilli), wake?.let(Instant::ofEpochMilli), now, ZoneId.systemDefault())
    }
```

- [ ] **Step 4: `WatchImUp`.** Create `app-wear/src/main/kotlin/com/trepidity/good/wear/sleep/WatchImUp.kt`:

```kotlin
package com.trepidity.good.wear.sleep

import android.content.Context
import com.trepidity.good.model.Command
import com.trepidity.good.model.Device
import com.trepidity.good.model.WakeAnchorMessage
import com.trepidity.good.sync.DataLayerPaths
import com.trepidity.good.sync.SyncCodec
import com.trepidity.good.wake.ImUp
import com.trepidity.good.wake.WakeEvent
import com.trepidity.good.wake.WakeStateMachine
import com.trepidity.good.wear.WearApplication
import com.trepidity.good.wear.alarm.WatchAlarmScheduler
import com.trepidity.good.wear.alarm.WatchScheduleStore
import com.trepidity.good.wear.surface.WatchSurfaces
import com.trepidity.good.wear.sync.WatchSync
import com.trepidity.good.wear.wake.WakeStageService
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * I'M UP on the watch: close this morning's alarms here at once (so it works with the phone out of range), then
 * tell the phone through the outbox: one dismiss per alarm and the wake time (I'M UP spec).
 */
object WatchImUp {
    fun record(context: Context, at: Instant): Int {
        val entries = WatchScheduleStore.load(context)?.entries.orEmpty()
        val targets = ImUp.targets(entries, at, ZoneId.systemDefault())
        val app = context.applicationContext as WearApplication
        for (e in targets) {
            val closed = WakeStateMachine.reduce(e.instance, WakeEvent.Dismiss(Device.WATCH), at, e.profile)
            val cmd = Command(closed.id, at.toEpochMilli(), Device.WATCH)
            WatchScheduleStore.update(context, closed)
            WatchAlarmScheduler.cancelChannel(context, closed.alarmId)
            WakeStageService.remoteCommands.tryEmit(cmd)
            app.appScope.launch { runCatching { WatchSync.sendDismiss(context, closed, cmd) } }
        }
        WatchScheduleStore.recordLocalWake(context, at.toEpochMilli())
        app.appScope.launch { WatchSync.send(context, DataLayerPaths.SLEEP_WAKE, SyncCodec.encodeAny(WakeAnchorMessage(at.toEpochMilli()))) }
        WatchSurfaces.refresh(context)
        return targets.size
    }
}
```

(Check `WakeStageService.remoteCommands` accepts a `Command`: it does, `WatchListenerService` already emits to it.)

- [ ] **Step 5: SLP hold in the app.** In `WatchApp.set()`, replace the `Mode.SLP -> { … }` branch with:

```kotlin
            Mode.SLP -> {
                val at = System.currentTimeMillis()
                if (WatchScheduleStore.sleepAction(context) == SleepAction.UP) {
                    WatchImUp.record(context, Instant.ofEpochMilli(at))
                    flash = Flash(Mode.SLP, "GOOD MORNING")
                } else {
                    WatchScheduleStore.recordLocalBed(context, at)
                    flash = Flash(Mode.SLP, "GOOD NIGHT")
                    app.appScope.launch { WatchSync.send(app, DataLayerPaths.SLEEP_BEDTIME, SyncCodec.encodeAny(BedtimeMessage(at))) }
                }
            }
```

Change `setLabel` for `Mode.SLP` to `if (WatchScheduleStore.sleepAction(context) == SleepAction.UP) "Log wake-up now" else "Log bedtime now"`. If "GOOD MORNING" doesn't fit `SleepBody`'s message line on the 454×454 round face, use "MORNING" and note it in the report.

- [ ] **Step 6: Tile.** In `GoodTileService`: the clickable's label becomes `if (WatchScheduleStore.sleepAction(this) == SleepAction.UP) "UP" else "BED"` (find where `CLICK_BED`'s button text `"BED"` is built). In `onTileRequest`, replace the `bed`/`lines` logic with:

```kotlin
        val pressed = requestParams.currentState.lastClickableId == CLICK_BED
        val lines = when {
            !pressed -> listOf(alarmLine(), sleepLine())
            else -> sleepButton()
        }
```

and replace `logBedtime()` with:

```kotlin
    /** BED or UP, once per press; a refresh repeating the last click id within a minute doesn't log it again. */
    private fun sleepButton(): List<String> {
        val now = System.currentTimeMillis()
        if (now - lastBedAt < 60_000) return lastLines
        lastBedAt = now
        lastLines = if (WatchScheduleStore.sleepAction(this) == SleepAction.UP) {
            WatchImUp.record(this, Instant.ofEpochMilli(now))
            listOf("GOOD", "MORNING")
        } else {
            WatchScheduleStore.recordLocalBed(this, now)
            (application as WearApplication).appScope.launch {
                WatchSync.send(this@GoodTileService, DataLayerPaths.SLEEP_BEDTIME, SyncCodec.encodeAny(BedtimeMessage(now)))
            }
            listOf("GOOD", "NIGHT")
        }
        return lastLines
    }
```

Add `private var lastLines = listOf("GOOD", "NIGHT")` next to `lastBedAt` (wherever `lastBedAt` is declared — keep the same scope). Update the class KDoc: "…with a BED/UP button that logs bedtime or I'M UP…".

- [ ] **Step 7: Build and lint.** Run the build + lint command. Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit.**

```bash
git add app-wear
git commit -m "Watch: apply close state, BED/UP toggle, I'M UP that works offline

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Docs, UAT rows, deletion pass

**Files:**
- Modify: `docs/SPEC.md`, `README.md`, `docs/REVIEW.md`

**Interfaces:** none.

- [ ] **Step 1: SPEC.md.** Apply the spec's "SPEC.md updates when this lands" list:
  - Requirements table: add `| F13 | Skip the next occurrence of a repeating alarm without affecting sleep tracking | Should |` and `| F14 | I'M UP: the bed button toggles to GOOD MORNING, which records the wake time and closes this morning's alarms | Should |`.
  - UX design → Modes table: ALM `▲ / ▼` cell add "hold ▼ 2 s: skip or unskip the next occurrence"; SLP `SET` cell becomes "Log bedtime now (GOOD NIGHT), or after it, the wake time (GOOD MORNING, I'M UP)…"; SLP `LCD shows` adds "the wake line (WOKE SND +6, UP EARLY, SKIPPED, NO ANSWER)"; CHK list adds `USE`.
  - Sleep tracking → step 2: "On dismiss or I'M UP, GOOD writes a provisional session…"; add step 2a: "A night whose alarm was skipped and has no I'M UP ends at the first phone unlock or watch "awake" after the skipped time, if under 24 h (#1)." Step 3: add "and a daily pass at 11:00".
  - Metrics: replace the wake-behaviour bullet with the built version.
  - Data Layer table: add `/sleep/wake` (Message, watch → phone, I'M UP time); note `Command.state`; `/sleep/summary` gains the anchor fields.
  - Data model: `AlarmInstance` gains `firstStageAt`, `dismissedAtStage`, state `CANCELLED`; "SKIPPED is written by SKIP only; I'M UP closes occurrences as DISMISSED before ringing; CANCELLED means the channel was edited or disarmed".
  - Permissions: `PACKAGE_USAGE_STATS` | Phone | Unlock times for a skipped night's wake | Settings → Usage access (CHK → USE).
  - Update the "As of" date to 2026-09-30.

- [ ] **Step 2: README.** Add UAT rows 13–19 from the spec's Device checks table, plus `| 20 | Install this build over the previous one (don't uninstall) | Alarms, history and sleep nights are all still there |`. Add `USE` to row 1's CHK list. Under "Known limits" add: "Install the phone and watch apps together: an older app can't read the new CANCELLED state." Update the Tests paragraph to mention skip/cancel decisions, the current-occurrence rule, I'M UP targets, the BED/UP toggle, inferred wake and wake behaviour.

- [ ] **Step 3: REVIEW.md.** Add a short section "Skip next and I'M UP (2026-09-30)" listing: #5 and #6 fixed (CANCELLED), the current-occurrence rule found during planning (a CANCELLED future row would have made moving an alarm earlier skip today), #7 built, #1 decided and built; #2 and #4 closed as won't do; #3 and #8 open.

- [ ] **Step 4: Deletion pass (test-selection).** For every test added in Tasks 1–2, write one line in the commit body: the gate it protects and one mutation that makes it fail (e.g. "`13 59 is a target and 14 00 is not` — change `<` to `<=` in `ImUp`"). Actually apply each mutation locally, run `./gradlew :core:wake:test :core:sleep:test --offline`, confirm red, revert. Delete any test with no failing mutation.

- [ ] **Step 5: Final verification.** Run the core test command and the build + lint command. Expected: both succeed.

- [ ] **Step 6: Commit.**

```bash
git add docs README.md
git commit -m "Docs: skip and I'M UP in SPEC, README UAT 13-20, REVIEW notes

<deletion-pass lines>

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
