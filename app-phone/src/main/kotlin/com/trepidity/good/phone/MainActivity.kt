package com.trepidity.good.phone

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.ScheduleEntry
import com.trepidity.good.model.ScheduleSnapshot
import com.trepidity.good.model.SoundTarget
import com.trepidity.good.model.WakeProfile
import com.trepidity.good.phone.alarm.AlarmScheduler
import com.trepidity.good.phone.alarm.ScheduleStore
import com.trepidity.good.phone.spike.CheckItem
import com.trepidity.good.phone.spike.HealthConnectProbe
import com.trepidity.good.phone.spike.ReliabilityCheck
import com.trepidity.good.phone.sync.PhoneSync
import com.trepidity.good.phone.wake.WakeService
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * M0 spike screen: reliability check, a 60-second preview, real test alarms shared with the watch,
 * and a Health Connect probe. Replaced by the real Alarms / Sleep / Settings screens in M1–M4.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { SpikeScreen() }
            }
        }
    }

    @Composable
    private fun SpikeScreen() {
        val scope = rememberCoroutineScope()
        val log = remember { mutableStateListOf<String>() }
        val checks = remember { mutableStateOf(ReliabilityCheck.run(this)) }
        fun say(line: String) {
            log.add(0, "${Instant.now().atZone(ZoneId.systemDefault()).format(HHMMSS)}  $line")
        }

        val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            say("Notifications ${if (it) "granted" else "denied"}")
            checks.value = ReliabilityCheck.run(this)
        }
        val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            say("Health Connect granted ${it.size} of ${HealthConnectProbe.PERMISSIONS.size} permissions")
        }

        LaunchedEffect(Unit) {
            say("Watch reachable: ${PhoneSync.isWatchReachable(this@MainActivity)}")
            say("Health Connect: ${HealthConnectProbe.status(this@MainActivity)}")
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("GOOD · M0 spike", style = MaterialTheme.typography.headlineMedium)

            Section("Reliability check")
            checks.value.forEach { CheckRow(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
                OutlinedButton(onClick = { checks.value = ReliabilityCheck.run(this@MainActivity) }) { Text("Re-check") }
            }

            Section("Wake-up")
            Button(onClick = { preview(); say("Preview started: whole Gentle profile in about 60 s, phone only") }, Modifier.fillMaxWidth()) {
                Text("Preview wake-up (60 s)")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { say(testAlarm(minutes = 3)) } }) { Text("Test alarm +3 min") }
                Button(onClick = { scope.launch { say(testAlarm(minutes = 12)) } }) { Text("+12 min (full ramp)") }
            }
            OutlinedButton(onClick = { scope.launch { say(cancelAll()) } }) { Text("Cancel test alarms") }

            Section("Sleep data")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { hcLauncher.launch(HealthConnectProbe.PERMISSIONS) }) { Text("Grant sleep access") }
                Button(onClick = {
                    scope.launch {
                        if (!HealthConnectProbe.isAvailable(this@MainActivity)) return@launch say("Health Connect unavailable")
                        if (!HealthConnectProbe.hasPermissions(this@MainActivity)) return@launch say("Grant sleep access first")
                        HealthConnectProbe.recentSleep(this@MainActivity).reversed().forEach(::say)
                    }
                }) { Text("Read last 7 nights") }
            }

            Section("Log")
            log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    @Composable
    private fun Section(title: String) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleMedium)
    }

    @Composable
    private fun CheckRow(item: CheckItem) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${if (item.ok) "OK " else "FIX"}  ${item.name}")
            if (!item.ok && item.fix != null) TextButton(onClick = { runCatching { startActivity(item.fix) } }) { Text("Fix") }
        }
    }

    private fun preview() {
        val entry = entry(Instant.now().plusSeconds(30), "Preview")
        val existing = ScheduleStore.load(this)?.entries.orEmpty()
        ScheduleStore.save(this, ScheduleSnapshot(System.currentTimeMillis(), System.currentTimeMillis(), existing + entry))
        ContextCompat.startForegroundService(this, WakeService.startIntent(this, entry.instance.id, preview = true))
    }

    /** A real alarm on both devices: the phone schedules it and publishes it for the watch to schedule too. */
    private suspend fun testAlarm(minutes: Long): String {
        val fireAt = Instant.now().plus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS)
        val entry = entry(fireAt, "Test +$minutes min")
        val snapshot = ScheduleSnapshot(System.currentTimeMillis(), System.currentTimeMillis(), listOf(entry))
        ScheduleStore.load(this)?.entries?.forEach { AlarmScheduler.cancel(this, it.instance.id) }
        ScheduleStore.save(this, snapshot)
        val ok = AlarmScheduler.schedule(this, entry)
        val pushed = runCatching { PhoneSync.pushSchedule(this, snapshot) }.isSuccess
        val at = fireAt.atZone(ZoneId.systemDefault()).format(HHMMSS)
        return "Test alarm T=$at · phone ${if (ok) "scheduled" else "REFUSED"} · watch ${if (pushed) "sent" else "not sent"}"
    }

    private suspend fun cancelAll(): String {
        ScheduleStore.load(this)?.entries?.forEach { AlarmScheduler.cancel(this, it.instance.id) }
        ScheduleStore.clear(this)
        val empty = ScheduleSnapshot(System.currentTimeMillis(), System.currentTimeMillis(), emptyList())
        runCatching { PhoneSync.pushSchedule(this, empty) }
        return "Cancelled test alarms on phone and watch"
    }

    private fun entry(fireAt: Instant, label: String) = ScheduleEntry(
        instance = AlarmInstance(id = "t${fireAt.toEpochMilli()}", alarmId = 0, scheduledAtEpochMs = fireAt.toEpochMilli()),
        profile = WakeProfile.GENTLE,
        soundTarget = SoundTarget.AUTO,
        label = label,
    )

    private companion object {
        val HHMMSS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
