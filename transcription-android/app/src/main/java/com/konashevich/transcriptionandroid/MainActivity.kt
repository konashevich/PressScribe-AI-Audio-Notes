package com.konashevich.pressscribe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.konashevich.pressscribe.data.VolumeButtonMode
import com.konashevich.pressscribe.ui.TranscriptionApp
import com.konashevich.pressscribe.ui.theme.PressScribeTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val heldVolumeKeys = mutableSetOf<Int>()
    private var pendingHardwareStartOnPermissionGrant = false
    private var holdVolumeOwnsCurrentRecording = false
    private val shareDedupePrefs by lazy {
        getSharedPreferences("share_dedupe", MODE_PRIVATE)
    }
    private val recordPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingHardwareStartOnPermissionGrant && !viewModel.uiState.value.isRecording) {
            viewModel.startRecording()
        }
        pendingHardwareStartOnPermissionGrant = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val state = viewModel.uiState.collectAsStateWithLifecycle()
            PressScribeTheme(themeMode = state.value.settings.themeMode) {
                TranscriptionApp(
                    state = state.value,
                    viewModel = viewModel,
                )
            }
        }

        handleIncomingIntent(
            intent = intent,
            isFreshLaunch = savedInstanceState == null,
            fromNewIntent = false,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(
            intent = intent,
            isFreshLaunch = true,
            fromNewIntent = true,
        )
    }

    override fun onPause() {
        releaseHeldVolumeRecording()
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (!viewModel.uiState.value.settings.welcomeCompleted) {
                // Until first-run setup finishes, do not treat volume as a record control.
                return super.dispatchKeyEvent(event)
            }
            val handled = when (viewModel.uiState.value.settings.volumeButtonMode) {
                VolumeButtonMode.HOLD_ANY -> handleHoldAnyVolumeEvent(event)
                VolumeButtonMode.TOGGLE_SPLIT -> handleToggleSplitVolumeEvent(event)
            }
            if (handled) {
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun handleIncomingIntent(
        intent: Intent?,
        isFreshLaunch: Boolean,
        fromNewIntent: Boolean,
    ) {
        if (intent == null) {
            return
        }

        val fromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        val uris = extractAudioUris(intent)

        // Config change / history restore: do not re-import. Only clear the queue when
        // this restore still carries a share payload (a real replay), not on plain rotation.
        if (!isFreshLaunch || fromHistory) {
            if (uris.isNotEmpty()) {
                viewModel.clearPendingSharedImports()
                consumeHandledShareIntent(intent)
            }
            return
        }

        if (uris.isEmpty()) {
            // Normal launcher open — allow the same file to be shared again later.
            clearShareSignature()
            return
        }

        val signature = uris.joinToString("\n") { it.toString() }
        val lastSignature = shareDedupePrefs.getString(KEY_LAST_SHARE_SIGNATURE, null)
        val lastAt = shareDedupePrefs.getLong(KEY_LAST_SHARE_AT, 0L)
        val now = System.currentTimeMillis()
        // OEM process-death reopen can look like a fresh onCreate without HISTORY.
        // Time-box so intentional re-shares of the same file still work.
        val recentDuplicate = !fromNewIntent &&
            signature == lastSignature &&
            now - lastAt in 0 until SHARE_DEDUPE_WINDOW_MS

        if (recentDuplicate) {
            viewModel.clearPendingSharedImports()
            consumeHandledShareIntent(intent)
            return
        }

        shareDedupePrefs.edit()
            .putString(KEY_LAST_SHARE_SIGNATURE, signature)
            .putLong(KEY_LAST_SHARE_AT, now)
            .apply()
        viewModel.handleSharedAudioUris(uris)
        consumeHandledShareIntent(intent)
    }

    private fun clearShareSignature() {
        shareDedupePrefs.edit()
            .remove(KEY_LAST_SHARE_SIGNATURE)
            .remove(KEY_LAST_SHARE_AT)
            .apply()
    }

    private fun handleHoldAnyVolumeEvent(event: KeyEvent): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) {
                    true
                } else {
                    val wasEmpty = heldVolumeKeys.isEmpty()
                    heldVolumeKeys.add(event.keyCode)
                    if (wasEmpty && !viewModel.uiState.value.isRecording) {
                        if (ensureRecordPermissionForHardware(autoStartOnGrant = false)) {
                            holdVolumeOwnsCurrentRecording = viewModel.uiState.value.isRecording
                        }
                    }
                    true
                }
            }

            KeyEvent.ACTION_UP -> {
                heldVolumeKeys.remove(event.keyCode)
                if (heldVolumeKeys.isEmpty() && holdVolumeOwnsCurrentRecording) {
                    holdVolumeOwnsCurrentRecording = false
                    viewModel.stopRecording()
                }
                true
            }

            else -> true
        }
    }

    private fun handleToggleSplitVolumeEvent(event: KeyEvent): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) {
                    true
                } else {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_VOLUME_UP -> {
                            if (!viewModel.uiState.value.isRecording) {
                                ensureRecordPermissionForHardware(autoStartOnGrant = true)
                            }
                            true
                        }

                        KeyEvent.KEYCODE_VOLUME_DOWN -> {
                            if (viewModel.uiState.value.isRecording) {
                                viewModel.stopRecording()
                            }
                            true
                        }

                        else -> false
                    }
                }
            }

            KeyEvent.ACTION_UP -> true
            else -> true
        }
    }

    private fun ensureRecordPermissionForHardware(autoStartOnGrant: Boolean): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            viewModel.startRecording()
            return true
        }

        pendingHardwareStartOnPermissionGrant = autoStartOnGrant
        recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        return false
    }

    private fun releaseHeldVolumeRecording() {
        heldVolumeKeys.clear()
        pendingHardwareStartOnPermissionGrant = false
        if (holdVolumeOwnsCurrentRecording) {
            holdVolumeOwnsCurrentRecording = false
            viewModel.stopRecording()
        }
    }

    private fun consumeHandledShareIntent(intent: Intent?) {
        if (intent == null) {
            return
        }

        setIntent(
            Intent(intent).apply {
                action = null
                data = null
                type = null
                clipData = null
                replaceExtras(Bundle())
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun extractAudioUris(intent: Intent?): List<Uri> {
        return when (intent?.action) {
            Intent.ACTION_SEND -> {
                listOfNotNull(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    },
                )
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                } else {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                }
            }

            else -> emptyList()
        }
    }

    companion object {
        private const val KEY_LAST_SHARE_SIGNATURE = "last_share_signature"
        private const val KEY_LAST_SHARE_AT = "last_share_at"
        private const val SHARE_DEDUPE_WINDOW_MS = 120_000L
    }
}
