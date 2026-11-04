package com.ai.assistance.operit.ui.floating.ui.fullscreen

import androidx.compose.runtime.Composable
import com.ai.assistance.operit.ui.floating.FloatContext
import com.ai.assistance.operit.ui.floating.ui.fullscreen.components.ShaheenLiveCallScreen

/**
 * Fullscreen floating mode is Shaheen 3's product Live call surface.
 *
 * The legacy cascade screen remains in the tree, but it is no longer what
 * FloatingMode.FULLSCREEN opens: the call is owned by
 * AIForegroundService and rendered by ShaheenLiveCallScreen from that
 * service's published flows.
 */
@Composable
fun FloatingFullscreenMode(floatContext: FloatContext) {
    ShaheenLiveCallScreen(floatContext)
}
