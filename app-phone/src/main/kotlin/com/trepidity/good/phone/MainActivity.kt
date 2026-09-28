package com.trepidity.good.phone

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.trepidity.good.phone.ui.Effect
import com.trepidity.good.phone.ui.Instrument
import com.trepidity.good.phone.ui.InstrumentModel
import kotlinx.coroutines.launch

/** GOOD is one instrument: an LCD panel and five case buttons (SPEC UX design). */
class MainActivity : ComponentActivity() {
    private val model: InstrumentModel by viewModels()

    private val healthConnect = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        model.runChecks()
        lifecycleScope.launch { AppGraph.sleep(this@MainActivity).syncRecent() }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model.runChecks() }
    private val activity = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) AppGraph.sleep(this).ensureSleepApi()
        model.runChecks()
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(model::export)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { Instrument(model) }
        lifecycleScope.launch {
            model.effects.collect { effect ->
                when (effect) {
                    is Effect.Open -> runCatching { startActivity(effect.intent) }
                    Effect.RequestHealthConnect -> healthConnect.launch(AppGraph.sleep(this@MainActivity).healthConnect.requestedPermissions())
                    Effect.RequestNotifications -> notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    Effect.RequestActivityRecognition -> activity.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    Effect.PickExportFile -> export.launch("good-export.json")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.onResume()
    }
}
