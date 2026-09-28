package com.trepidity.good.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shown by Health Connect when you ask why GOOD wants sleep access. */
class HealthPermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Text(
                    "GOOD reads your sleep sessions (for example from OHealth) to show how long you slept, " +
                        "and writes sessions it detects itself. Your data never leaves this phone: GOOD has no internet access.",
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}
