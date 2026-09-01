package dev.pumplink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pumplink.presentation.BolusScenario
import dev.pumplink.presentation.BolusScenarios

/**
 * Debug-only picker over [BolusScenarios]. Launch with
 * `adb shell am start -n dev.pumplink/.StateGalleryActivity`, or pass
 * `--es scenario "Awaiting reissue"` to open a named row without the picker
 * (used by `tools/capture-states.sh`).
 */
class StateGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requested = mutableStateOf(intent.getStringExtra(EXTRA_SCENARIO))
        addOnNewIntentListener { incoming ->
            setIntent(incoming)
            requested.value = incoming.getStringExtra(EXTRA_SCENARIO)
        }
        setContent {
            PumpLinkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Gallery(requested.value)
                }
            }
        }
    }

    companion object {
        const val EXTRA_SCENARIO = "scenario"
    }
}

@Composable
private fun Gallery(requested: String?) {
    val fromIntent = BolusScenarios.all.firstOrNull { it.name == requested }
    var selected by remember {
        mutableStateOf(fromIntent ?: BolusScenarios.all.first())
    }
    LaunchedEffect(requested) {
        fromIntent?.let { selected = it }
    }
    val scripted = requested != null
    Column(modifier = Modifier.fillMaxSize()) {
        if (!scripted) {
            Text(
                "State gallery",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            ScenarioPicker(selected) { selected = it }
        }
        BolusScreen(
            state = selected.screen(),
            onIntent = { },
            onOpenLink = { },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ScenarioPicker(selected: BolusScenario, onSelect: (BolusScenario) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BolusScenarios.all.forEach { scenario ->
            val active = scenario.name == selected.name
            Text(
                scenario.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clickable { onSelect(scenario) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            )
        }
    }
}
