package com.ai.assistance.operit.ui.floating.ui.ball

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.api.voice.RealtimeSessionState
import com.ai.assistance.operit.ui.floating.FloatContext
import com.ai.assistance.operit.ui.floating.FloatingMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Voice-ball floating mode.
 *
 * During a Live call this is the background mini-orb: it renders
 * AIForegroundService's state and microphone RMS, a tap returns to the
 * fullscreen call screen, and a long press ends the call through the service.
 * Outside a Live call it keeps the existing voice-ball result behaviour.
 */
@Composable
fun FloatingVoiceBallMode(floatContext: FloatContext) {
    val context = LocalContext.current
    val liveCallState by AIForegroundService.liveCallState.collectAsState()
    val liveAudioLevel by AIForegroundService.liveCallAudioLevel.collectAsState()
    val liveCallActive =
        liveCallState != RealtimeSessionState.Ended &&
            liveCallState !is RealtimeSessionState.Error

    SiriBall(
        floatContext = floatContext,
        onClick = {
            floatContext.onModeChange(FloatingMode.FULLSCREEN)
        },
        onTriggerResult = {
            if (!liveCallActive) {
                // 切换到结果展示模式显示结果
                floatContext.onModeChange(FloatingMode.RESULT_DISPLAY)

                // 3秒后自动切回球模式
                floatContext.coroutineScope.launch {
                    delay(3000)
                    // 只有当前还是结果展示模式时才切回（避免用户已经切换到其他模式）
                    if (floatContext.currentMode == FloatingMode.RESULT_DISPLAY) {
                        floatContext.onModeChange(FloatingMode.BALL)
                    }
                }
            }
        },
        liveCallState = if (liveCallActive) liveCallState else null,
        liveAudioLevel = if (liveCallActive) liveAudioLevel else 0f,
        onLongClick = {
            if (liveCallActive) {
                AIForegroundService.endLiveCall(context)
            }
        }
    )
}
