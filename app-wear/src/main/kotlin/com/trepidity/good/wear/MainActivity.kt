package com.trepidity.good.wear

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.trepidity.good.wear.alarm.WatchScheduleStore
import com.trepidity.good.wear.spike.WatchProbe
import com.trepidity.good.wear.wake.WakeStageService
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** M0 spike on the watch: test haptics and speaker, report capabilities, show the synced schedule. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Spike() } }
    }

    @Composable
    private fun Spike() {
        val scope = rememberCoroutineScope()
        val lines = remember { mutableStateListOf<String>() }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            lines.add(0, "Permissions: ${it.values.count { granted -> granted }}/${it.size}")
        }

        ScalingLazyColumn(Modifier.fillMaxSize()) {
            item { Text("GOOD · M0", fontSize = 18.sp) }
            item { Action("Haptic ramp 30 s") { service(WakeStageService.ACTION_TEST_HAPTICS) } }
            item { Action("Sound ramp 30 s") { service(WakeStageService.ACTION_TEST_SOUND) } }
            item { Action("Stop test") { startService(WakeStageService.actionIntent(this@MainActivity, WakeStageService.ACTION_STOP_TEST)) } }
            item {
                Action("Allow permissions") {
                    permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACTIVITY_RECOGNITION))
                }
            }
            item {
                Action("Check watch") {
                    lines.clear()
                    lines.addAll(WatchProbe.basics(this@MainActivity))
                    scope.launch { lines.addAll(WatchProbe.healthServices(this@MainActivity)) }
                    lines.add(nextAlarm())
                }
            }
            items(lines.size) { i ->
                Text(lines[i], fontSize = 11.sp, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    @Composable
    private fun Action(label: String, onClick: () -> Unit) {
        Chip(onClick = onClick, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
    }

    private fun service(action: String) {
        ContextCompat.startForegroundService(this, WakeStageService.actionIntent(this, action))
    }

    private fun nextAlarm(): String {
        val snap = WatchScheduleStore.load(this) ?: return "No schedule from phone yet"
        val next = snap.entries.filter { !it.instance.state.isTerminal }.minByOrNull { it.instance.scheduledAtEpochMs }
            ?: return "Schedule v${snap.version}: nothing pending"
        val at = Instant.ofEpochMilli(next.instance.scheduledAtEpochMs).atZone(ZoneId.systemDefault())
        return "Schedule v${snap.version}: next ${at.format(DateTimeFormatter.ofPattern("EEE HH:mm:ss"))}"
    }
}
