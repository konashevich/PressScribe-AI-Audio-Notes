package com.konashevich.pressscribe.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.konashevich.pressscribe.data.GEMINI_API_KEYS_URL
import com.konashevich.pressscribe.data.ListenMode
import com.konashevich.pressscribe.data.VolumeButtonMode
import com.konashevich.pressscribe.data.isPlausibleGeminiApiKey

@Composable
fun WelcomeSetupScreen(
    sessionId: Int,
    initialApiKey: String,
    initialListenMode: ListenMode?,
    initialVolumeMode: VolumeButtonMode?,
    isFirstRun: Boolean,
    isTestingApiKey: Boolean,
    isCompletingWelcome: Boolean,
    onQuit: () -> Unit,
    onDismiss: () -> Unit,
    onTestApiKey: (String) -> Unit,
    onComplete: (apiKey: String, listenMode: ListenMode, volumeMode: VolumeButtonMode) -> Unit,
) {
    val context = LocalContext.current
    var page by rememberSaveable(sessionId) { mutableIntStateOf(0) }
    var apiKey by rememberSaveable(sessionId) { mutableStateOf(initialApiKey) }
    var showKey by rememberSaveable(sessionId) { mutableStateOf(false) }
    var pendingListen by rememberSaveable(sessionId) {
        mutableStateOf(initialListenMode?.name)
    }
    var pendingVolume by rememberSaveable(sessionId) {
        mutableStateOf(initialVolumeMode?.name)
    }

    val listenMode = pendingListen?.let { runCatching { ListenMode.valueOf(it) }.getOrNull() }
    val volumeMode = pendingVolume?.let { runCatching { VolumeButtonMode.valueOf(it) }.getOrNull() }
    val canContinue = isPlausibleGeminiApiKey(apiKey)
    val canFinish = listenMode != null && volumeMode != null && !isCompletingWelcome
    val finishLabel = if (isFirstRun) "Get started" else "Done"
    val busy = isTestingApiKey || isCompletingWelcome

    fun onSecondaryAction() {
        when {
            page == 1 -> page = 0
            isFirstRun -> onQuit()
            else -> onDismiss()
        }
    }

    BackHandler(enabled = !isCompletingWelcome) {
        onSecondaryAction()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            ProgressBars(activeCount = if (page == 0) 1 else 2)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Setup · ${page + 1} / 2",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (page == 0) {
                    Text(
                        text = "PressScribe",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Transcribe Your Audio Notes with AI",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PitchBullet(text = "PressScribe is genuinely free.")
                    PitchBullet(text = "BYOK: but you need your own API key")
                    Text(
                        text = "Gemini API key",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Google’s cloud AI is not free — get a Gemini key from AI Studio, then paste it here. Usage is billed to your Google account.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(GEMINI_API_KEYS_URL)),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    ) {
                        Text("Open API keys page")
                    }
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(GEMINI_API_KEYS_URL)),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    ) {
                        Text(GEMINI_API_KEYS_URL)
                    }
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API key") },
                        placeholder = { Text("Paste your Gemini API key") },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = if (showKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = {
                            Text("No spaces; at least 20 characters.")
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = showKey,
                                onCheckedChange = { showKey = it },
                                enabled = !busy,
                            )
                            Text("Show key")
                        }
                        OutlinedButton(
                            onClick = { onTestApiKey(apiKey) },
                            enabled = !busy && apiKey.trim().isNotEmpty(),
                        ) {
                            if (isTestingApiKey) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("Test key")
                            }
                        }
                    }
                } else {
                    Text(
                        text = "Two ways to record yourself",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Choose one option in each group.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Listen (record audio) button",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    RadioOptionCard(
                        title = "Press and Hold",
                        subtitle = "Hold to record. Release to stop.",
                        selected = listenMode == ListenMode.HOLD,
                        enabled = !busy,
                        onClick = { pendingListen = ListenMode.HOLD.name },
                    )
                    RadioOptionCard(
                        title = "Tap to Toggle",
                        subtitle = "Tap to start. Tap again to stop.",
                        selected = listenMode == ListenMode.TOGGLE,
                        enabled = !busy,
                        onClick = { pendingListen = ListenMode.TOGGLE.name },
                    )
                    Text(
                        text = "Volume buttons",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    RadioOptionCard(
                        title = "Hold either",
                        subtitle = "Hold Vol+ or Vol− to record. Release to stop.",
                        selected = volumeMode == VolumeButtonMode.HOLD_ANY,
                        enabled = !busy,
                        onClick = { pendingVolume = VolumeButtonMode.HOLD_ANY.name },
                    )
                    RadioOptionCard(
                        title = "Vol+ / Vol−",
                        subtitle = "Vol+ starts. Vol− stops.",
                        selected = volumeMode == VolumeButtonMode.TOGGLE_SPLIT,
                        enabled = !busy,
                        onClick = { pendingVolume = VolumeButtonMode.TOGGLE_SPLIT.name },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { onSecondaryAction() },
                    enabled = !isCompletingWelcome,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when {
                            page == 1 -> "Back"
                            isFirstRun -> "Quit"
                            else -> "Close"
                        },
                    )
                }
                Button(
                    onClick = {
                        if (page == 0) {
                            page = 1
                        } else if (listenMode != null && volumeMode != null) {
                            onComplete(apiKey, listenMode, volumeMode)
                        }
                    },
                    enabled = if (page == 0) canContinue && !busy else canFinish,
                    modifier = Modifier.weight(1f),
                ) {
                    if (page == 1 && isCompletingWelcome) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(if (page == 0) "Continue" else finishLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun PitchBullet(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ProgressBars(activeCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(2) { index ->
            val active = index < activeCount
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
    }
}

@Composable
private fun RadioOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(width = 1.5.dp, color = borderColor, shape = shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioDot(selected = selected)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .padding(top = 2.dp)
            .size(20.dp)
            .border(width = 2.dp, color = borderColor, shape = CircleShape)
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
