# GOOD

A gentle, two-device alarm for Android and Wear OS. Your phone's screen lights up first, then your watch buzzes, and only then does a sound start, quiet at first and getting louder until you dismiss it. GOOD also logs how long you slept. There is no snooze.

- Phone: OnePlus 12, OxygenOS 16 (Android 16)
- Watch: OnePlus Watch 2R, Wear OS 5
- Sleep data: OHealth, read through Health Connect

The full design lives in [docs/SPEC.md](docs/SPEC.md).

## Status: M0 spike

This first version is a test build. Its job is to answer the watch questions before any feature work starts:

| Question | Where to test it |
| --- | --- |
| Does the watch wake up and ring from its own alarm, even with Bluetooth off? | Phone → **Test alarm +3 min**, then switch Bluetooth off on the phone |
| Can the 2R's speaker start quiet and get louder? Are its haptics strong enough? | Watch → **Sound ramp 30 s** / **Haptic ramp 30 s** |
| Does Health Services on the 2R report asleep/awake states? | Watch → **Check watch** (look at "User states") |
| Does OHealth write sleep sessions (with stages) to Health Connect? | Phone → **Grant sleep access**, then **Read last 7 nights** |
| Does the full phone ramp look and sound right? | Phone → **Preview wake-up (60 s)** or **+12 min (full ramp)** |

## Layout

```
app-phone/   Phone app: spike screen, RingActivity (light ramp), WakeService, AlarmScheduler, Data Layer sync
app-wear/    Watch app: spike screen, WatchRingActivity, WakeStageService, watch scheduler, WatchListenerService
core/model/  Alarm, WakeProfile, Stage, AlarmInstance, ScheduleSnapshot (kotlinx.serialization)
core/wake/   WakePlanner (who runs which stage when), NextOccurrence, WakeStateMachine; pure Kotlin + tests
core/sync/   Data Layer paths and JSON codec
core/ring/   ToneRamp (soft chime that keeps getting louder) and HapticRamp; shared Android helpers
```

`core/data` (Room) arrives in M1 and `core/sleep` (session builder) in M3.

## Build and install (Android Studio)

1. Open the `GOOD` folder in Android Studio and let Gradle sync. The dependency versions are pinned to known releases from mid-2025, and nothing has been compiled against real Android SDKs yet. Accept the upgrade assistant's suggestions, and expect a few small fixes on the first sync.
2. **Phone:** turn on USB or wireless debugging, then run the `app-phone` configuration.
3. **Watch:** on the 2R, open Settings → System → About → Versions and tap the build number seven times. Then go to Developer options and turn on **ADB debugging** and **Wireless debugging**, and pair and connect:
   ```
   adb pair <watch-ip>:<pair-port>     # enter the code shown on the watch
   adb connect <watch-ip>:<port>
   ```
   Then run the `app-wear` configuration on the watch.
4. Both apps must be signed with the **same key** and share the application ID `com.trepidity.good`, or they won't see each other over the Data Layer. Debug builds from the same machine already share a key.
5. On the phone, open GOOD and fix anything the Reliability check flags. On OxygenOS, also set **Battery → GOOD → Unrestricted** (Settings → Apps → GOOD → Battery usage).

## Tests

```
./gradlew :core:wake:test :core:sync:test
```

The `core/wake` tests cover stage planning (including the phone taking over when the watch is missing), DST gaps, repeat days and the dismiss/auto-silence state machine.
