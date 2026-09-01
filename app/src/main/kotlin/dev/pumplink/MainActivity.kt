package dev.pumplink

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: BolusViewModel by viewModels()

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) {
            viewModel.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePermissions()
        setContent {
            PumpLinkTheme {
                Surface {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    var linkVisible by remember { mutableStateOf(false) }

                    BolusScreen(
                        state = state,
                        onIntent = viewModel::onIntent,
                        onOpenLink = { linkVisible = true },
                    )

                    LinkSheetOverlay(
                        visible = linkVisible,
                        onDismiss = { linkVisible = false },
                    ) {
                        LinkSheet(
                            link = state.link,
                            progress = state.progress,
                            identity = viewModel.pairedIdentity,
                            pump = state.pump,
                            latestCommand = state.history.firstOrNull(),
                            onStart = viewModel::start,
                            onStop = viewModel::stop,
                            onDismiss = { linkVisible = false },
                        )
                    }
                }
            }
        }
    }

    private fun requestBlePermissions() {
        val needed = buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permissions.launch(needed)
    }
}
