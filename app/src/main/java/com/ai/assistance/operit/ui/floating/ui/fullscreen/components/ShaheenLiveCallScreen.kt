package com.ai.assistance.operit.ui.floating.ui.fullscreen.components

import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.api.voice.RealtimeSessionState
import com.ai.assistance.operit.ui.common.WaveVisualizer
import com.ai.assistance.operit.ui.floating.FloatContext
import com.ai.assistance.operit.ui.floating.FloatingMode
import kotlinx.coroutines.launch

private val MidnightNavy = Color(0xFF000B1D)
private val DeepNavy = Color(0xFF001A38)
private val PanelNavy = Color(0xFF082747)
private val ShaheenRed = Color(0xFFE53935)
private val ShaheenCyan = Color(0xFF35C4FF)
private val ShaheenViolet = Color(0xFF9B6DFF)
private val CaptionWhite = Color(0xFFF4F8FC)

/**
 * Product Live call surface for Shaheen 3.
 *
 * AIForegroundService is the only session owner. This composable deliberately
 * holds no session object: it renders the service's published state and sends
 * the service's companion actions, so closing or backgrounding this window
 * cannot end a call.
 */
@Composable
fun ShaheenLiveCallScreen(floatContext: FloatContext) {
    val context = LocalContext.current
    val liveState by AIForegroundService.liveCallState.collectAsState()
    val speakerEnabled by AIForegroundService.liveCallSpeakerEnabled.collectAsState()
    val autoEnterVoiceChat =
        remember(floatContext.chatService) {
            floatContext.chatService?.consumeAutoEnterVoiceChat() == true
        }

    // Existing wake, assistant and home-widget entries set the floating
    // service's auto-enter flag. Their product equivalent is an explicit Live
    // start; ordinary fullscreen entries leave the orb Sleeping for a tap.
    LaunchedEffect(autoEnterVoiceChat) {
        if (autoEnterVoiceChat) {
            AIForegroundService.startLiveCall(context)
        }
    }

    var userCaption by remember { mutableStateOf("") }
    var shaheenCaption by remember { mutableStateOf("") }

    // The service transcript flows have replay 0: this screen shows the lines
    // spoken while it is open, in the order and language they arrive. No test
    // labels are added around the conversation.
    LaunchedEffect(Unit) {
        launch {
            AIForegroundService.liveCallInputTranscripts.collect { transcript ->
                if (transcript.text.isNotBlank()) {
                    userCaption = transcript.text
                }
            }
        }
        launch {
            AIForegroundService.liveCallOutputTranscripts.collect { transcript ->
                if (transcript.text.isNotBlank()) {
                    shaheenCaption = transcript.text
                }
            }
        }
    }

    val isCallActive =
        liveState != RealtimeSessionState.Ended && liveState !is RealtimeSessionState.Error
    val isPaused = liveState == RealtimeSessionState.Paused
    val stateLabel =
        when (val state = liveState) {
            RealtimeSessionState.Ended -> stringResource(R.string.live_call_sleeping)
            RealtimeSessionState.Connecting -> stringResource(R.string.live_call_connecting)
            RealtimeSessionState.Connected -> stringResource(R.string.live_call_in_progress)
            RealtimeSessionState.Listening -> stringResource(R.string.live_call_listening)
            RealtimeSessionState.Thinking -> stringResource(R.string.live_call_thinking)
            RealtimeSessionState.Speaking -> stringResource(R.string.live_call_speaking)
            RealtimeSessionState.Interrupted -> stringResource(R.string.live_call_interrupted)
            RealtimeSessionState.Paused -> stringResource(R.string.live_call_paused)
            is RealtimeSessionState.Error -> stringResource(R.string.live_call_error_title)
        }
    // Shaheen 3 v1.0.1: the call screen speaks plain words only. Raw
    // exception text (e.g. an obfuscated "... was cancelled") is for the
    // logs, never for Malik.
    val errorText =
        (liveState as? RealtimeSessionState.Error)?.let { error ->
            when (error.cause) {
                is SecurityException -> stringResource(R.string.live_call_error_permission)
                is java.io.IOException -> stringResource(R.string.live_call_error_network)
                else -> stringResource(R.string.live_call_error_generic)
            }
        }

    val onOrbClick: () -> Unit = {
        when {
            liveState == RealtimeSessionState.Ended || liveState is RealtimeSessionState.Error -> {
                userCaption = ""
                shaheenCaption = ""
                AIForegroundService.startLiveCall(context)
            }
            isPaused -> AIForegroundService.resumeLiveCall(context)
            else -> Unit
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MidnightNavy)
                .padding(10.dp)
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        brush =
                            Brush.verticalGradient(
                                colors = listOf(DeepNavy, MidnightNavy, Color(0xFF000613))
                            ),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = ShaheenRed.copy(alpha = 0.88f),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(
                        start = 20.dp,
                        end = 20.dp,
                        top = 18.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                        bottom = 20.dp
                    )
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(PanelNavy)
                                    .border(1.dp, ShaheenRed.copy(alpha = 0.75f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter =
                                    rememberAsyncImagePainter(
                                        model = Uri.parse("file:///android_asset/shaheen.png")
                                    ),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(
                                text = stringResource(R.string.app_name),
                                color = CaptionWhite,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.live_call_greeting),
                                color = CaptionWhite.copy(alpha = 0.76f),
                                fontSize = 14.sp
                            )
                        }
                    }

                    IconButton(
                        enabled = isCallActive,
                        onClick = { floatContext.onModeChange(FloatingMode.VOICE_BALL) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = stringResource(R.string.live_call_background),
                            tint = CaptionWhite.copy(alpha = if (isCallActive) 0.9f else 0.35f)
                        )
                    }
                    IconButton(onClick = { floatContext.onClose() }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.live_call_close),
                            tint = CaptionWhite.copy(alpha = 0.9f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(26.dp))

                Text(
                    text = stateLabel,
                    color =
                        when {
                            liveState is RealtimeSessionState.Error -> ShaheenRed
                            isPaused -> CaptionWhite.copy(alpha = 0.62f)
                            else -> ShaheenCyan
                        },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )

                ShaheenLiveOrb(
                    state = liveState,
                    onClick = onOrbClick,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                )

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(82.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        liveState is RealtimeSessionState.Error -> {
                            Text(
                                text = errorText.orEmpty(),
                                color = Color(0xFFFFB4AE),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        liveState == RealtimeSessionState.Ended &&
                            userCaption.isBlank() && shaheenCaption.isBlank() -> {
                            Text(
                                text = stringResource(R.string.live_call_tap_to_start),
                                color = CaptionWhite.copy(alpha = 0.62f),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                        else -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                if (userCaption.isNotBlank()) {
                                    Text(
                                        text = userCaption,
                                        color = CaptionWhite.copy(alpha = 0.68f),
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (shaheenCaption.isNotBlank()) {
                                    Text(
                                        text = shaheenCaption,
                                        color = CaptionWhite,
                                        fontSize = 16.sp,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(56.dp),
                        enabled = isCallActive,
                        shape = RoundedCornerShape(28.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = PanelNavy,
                                contentColor = CaptionWhite,
                                disabledContainerColor = PanelNavy.copy(alpha = 0.4f),
                                disabledContentColor = CaptionWhite.copy(alpha = 0.4f)
                            ),
                        onClick = {
                            if (isPaused) {
                                AIForegroundService.resumeLiveCall(context)
                            } else {
                                AIForegroundService.pauseLiveCall(context)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.MicOff,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text =
                                stringResource(
                                    if (isPaused) R.string.live_call_resume else R.string.live_call_mute
                                ),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(56.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor =
                                    if (speakerEnabled) Color(0xFF075E86) else PanelNavy,
                                contentColor = CaptionWhite
                            ),
                        onClick = {
                            AIForegroundService.setLiveSpeakerEnabled(context, !speakerEnabled)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = stringResource(R.string.live_call_speaker),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                    enabled = isCallActive,
                    shape = RoundedCornerShape(32.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFC62828),
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFF5B2024),
                            disabledContentColor = Color.White.copy(alpha = 0.45f)
                        ),
                    onClick = { AIForegroundService.endLiveCall(context) }
                ) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = null,
                        modifier =
                            Modifier
                                .size(22.dp)
                                .graphicsLayer { rotationZ = 135f }
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        text = stringResource(R.string.live_call_end),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ShaheenLiveOrb(
    state: RealtimeSessionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val audioLevel by AIForegroundService.liveCallAudioLevel.collectAsState()
    val isWaveActive =
        state == RealtimeSessionState.Connecting ||
            state == RealtimeSessionState.Connected ||
            state == RealtimeSessionState.Listening ||
            state == RealtimeSessionState.Thinking ||
            state == RealtimeSessionState.Speaking ||
            state == RealtimeSessionState.Interrupted

    val accent =
        when (state) {
            RealtimeSessionState.Ended -> Color(0xFF39749E)
            RealtimeSessionState.Paused -> Color(0xFF71879A)
            is RealtimeSessionState.Error -> ShaheenRed
            RealtimeSessionState.Thinking -> ShaheenViolet
            else -> ShaheenCyan
        }

    val targetGlowLevel =
        when (state) {
            RealtimeSessionState.Listening -> audioLevel.coerceIn(0f, 1f)
            RealtimeSessionState.Speaking -> maxOf(audioLevel.coerceIn(0f, 1f), 0.45f)
            RealtimeSessionState.Thinking -> 0.22f
            RealtimeSessionState.Connecting -> 0.3f
            RealtimeSessionState.Connected, RealtimeSessionState.Interrupted -> 0.18f
            else -> 0f
        }
    val glowLevel by
        animateFloatAsState(
            targetValue = targetGlowLevel,
            animationSpec = tween(durationMillis = 140, easing = LinearEasing),
            label = "shaheen_live_orb_glow"
        )

    val transition = rememberInfiniteTransition(label = "shaheen_live_orb")
    val breathe by
        transition.animateFloat(
            initialValue = 0.97f,
            targetValue = if (isWaveActive) 1.04f else 1f,
            animationSpec =
                infiniteRepeatable(
                    animation =
                        tween(
                            durationMillis = if (state == RealtimeSessionState.Thinking) 2600 else 1800,
                            easing = FastOutSlowInEasing
                        ),
                    repeatMode = RepeatMode.Reverse
                ),
            label = "shaheen_live_orb_breathe"
        )
    val rotation by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec =
                infiniteRepeatable(
                    animation =
                        tween(
                            durationMillis = if (state == RealtimeSessionState.Thinking) 2400 else 7000,
                            easing = LinearEasing
                        ),
                    repeatMode = RepeatMode.Restart
                ),
            label = "shaheen_live_orb_rotation"
        )

    Box(
        modifier = modifier.alpha(if (state == RealtimeSessionState.Paused) 0.58f else 1f),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier =
                Modifier
                    .size(320.dp)
                    .clickable(
                        enabled =
                            state == RealtimeSessionState.Ended ||
                                state == RealtimeSessionState.Paused ||
                                state is RealtimeSessionState.Error,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseRadius = size.minDimension * 0.34f * breathe * (1f + glowLevel * 0.1f)

                drawCircle(
                    brush =
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    accent.copy(alpha = 0.34f + glowLevel * 0.3f),
                                    accent.copy(alpha = 0.12f),
                                    Color.Transparent
                                ),
                            center = center,
                            radius = baseRadius * 1.55f
                        ),
                    center = center,
                    radius = baseRadius * 1.55f
                )
                drawCircle(
                    brush =
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    Color.White.copy(alpha = 0.2f),
                                    accent.copy(alpha = 0.58f),
                                    Color(0xFF00152E).copy(alpha = 0.96f)
                                ),
                            center = center,
                            radius = baseRadius
                        ),
                    center = center,
                    radius = baseRadius
                )
                drawArc(
                    color = accent.copy(alpha = 0.8f),
                    startAngle = rotation,
                    sweepAngle = 105f,
                    useCenter = false,
                    topLeft =
                        Offset(
                            center.x - baseRadius * 1.14f,
                            center.y - baseRadius * 1.14f
                        ),
                    size = Size(baseRadius * 2.28f, baseRadius * 2.28f),
                    style = Stroke(width = 3.dp.toPx())
                )
                drawArc(
                    color = ShaheenViolet.copy(alpha = 0.62f),
                    startAngle = rotation + 180f,
                    sweepAngle = 80f,
                    useCenter = false,
                    topLeft =
                        Offset(
                            center.x - baseRadius * 1.24f,
                            center.y - baseRadius * 1.24f
                        ),
                    size = Size(baseRadius * 2.48f, baseRadius * 2.48f),
                    style = Stroke(width = 2.dp.toPx())
                )
                if (state == RealtimeSessionState.Speaking) {
                    drawCircle(
                        color = accent.copy(alpha = 0.3f + glowLevel * 0.22f),
                        center = center,
                        radius = baseRadius * (1.18f + glowLevel * 0.2f),
                        style = Stroke(width = (2f + glowLevel * 5f).dp.toPx())
                    )
                }
            }

            // Reuse the existing visualizer as the orb's living centre. It
            // receives the service's microphone RMS flow directly; the outer
            // Canvas adds the state colour, scale and glow around it.
            WaveVisualizer(
                modifier = Modifier.size(230.dp),
                isActive = isWaveActive,
                volumeFlow = if (isWaveActive) AIForegroundService.liveCallAudioLevel else null,
                waveColor = CaptionWhite,
                activeWaveColor = accent,
                onToggleActive = onClick
            )
        }
    }
}
