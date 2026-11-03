package com.ai.assistance.operit.core.tools.agent

import android.content.Context
import com.ai.assistance.showerclient.ShowerController as ClientShowerController
import java.util.concurrent.ConcurrentHashMap

/**
 * App-side Shower facade.
 *
 * Wave 4, step 1 deliberately severs Shower behaviour here before the
 * subsystem is deleted in the follow-up step. Display queries therefore
 * always report that Shower is unavailable, and server, display, capture,
 * and input operations never delegate to the Shower client. Controller
 * instances are retained only to preserve this facade's existing API.
 */
object ShowerController {

    private val instances = ConcurrentHashMap<String, ClientShowerController>()

    /** Gets or creates a controller instance for the given agentId. */
    fun getInstance(agentId: String): ClientShowerController {
        return instances.getOrPut(agentId) { ClientShowerController() }
    }

    /** Checks if a controller instance exists for the given agentId. */
    fun hasInstance(agentId: String): Boolean = instances.containsKey(agentId)

    /** Shower is disabled, so no display ID is ever reported. */
    fun getDisplayId(agentId: String): Int? = null

    fun getDisplayId(): Int? = getDisplayId("default")

    /** Shower is disabled, so no video size is ever reported. */
    fun getVideoSize(agentId: String): Pair<Int, Int>? = null

    fun getVideoSize(): Pair<Int, Int>? = getVideoSize("default")

    /** No Shower video stream is attached while the facade is disabled. */
    fun setBinaryHandler(agentId: String, handler: ((ByteArray) -> Unit)?) {
        // Intentionally no-op: do not connect the handler to a Shower stream.
    }

    fun setBinaryHandler(handler: ((ByteArray) -> Unit)?) = setBinaryHandler("default", handler)

    suspend fun requestScreenshot(agentId: String, timeoutMs: Long = 3000L): ByteArray? = null

    suspend fun requestScreenshot(timeoutMs: Long = 3000L): ByteArray? =
        requestScreenshot("default", timeoutMs)

    /** Shower input preparation for the physical main display is disabled. */
    suspend fun prepareMainDisplay(agentId: String, context: Context): Boolean = false

    suspend fun prepareMainDisplay(context: Context): Boolean =
        prepareMainDisplay("default", context)

    /** Shower virtual-display creation is disabled. */
    suspend fun ensureDisplay(
        agentId: String,
        context: Context,
        width: Int,
        height: Int,
        dpi: Int,
        bitrateKbps: Int? = null,
    ): Boolean = false

    suspend fun ensureDisplay(
        context: Context,
        width: Int,
        height: Int,
        dpi: Int,
        bitrateKbps: Int? = null,
    ): Boolean = ensureDisplay("default", context, width, height, dpi, bitrateKbps)

    /** App launches through Shower are disabled. */
    suspend fun launchApp(agentId: String, packageName: String): Boolean = false

    suspend fun launchApp(packageName: String): Boolean =
        launchApp("default", packageName)

    /** Tap input through Shower is disabled. */
    suspend fun tap(agentId: String, x: Int, y: Int): Boolean = false

    suspend fun tap(x: Int, y: Int): Boolean = tap("default", x, y)

    /** Swipe input through Shower is disabled. */
    suspend fun swipe(
        agentId: String,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = 300L,
    ): Boolean = false

    suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = 300L,
    ): Boolean = swipe("default", startX, startY, endX, endY, durationMs)

    /** Touch-down input through Shower is disabled. */
    suspend fun touchDown(agentId: String, x: Int, y: Int): Boolean = false

    suspend fun touchDown(x: Int, y: Int): Boolean = touchDown("default", x, y)

    /** Touch-move input through Shower is disabled. */
    suspend fun touchMove(agentId: String, x: Int, y: Int): Boolean = false

    suspend fun touchMove(x: Int, y: Int): Boolean = touchMove("default", x, y)

    /** Touch-up input through Shower is disabled. */
    suspend fun touchUp(agentId: String, x: Int, y: Int): Boolean = false

    suspend fun touchUp(x: Int, y: Int): Boolean = touchUp("default", x, y)

    suspend fun injectTouchEvent(
        agentId: String,
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
        pressure: Float,
        size: Float,
        metaState: Int,
        xPrecision: Float,
        yPrecision: Float,
        deviceId: Int,
        edgeFlags: Int,
    ): Boolean = false

    suspend fun injectTouchEvent(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
        pressure: Float,
        size: Float,
        metaState: Int,
        xPrecision: Float,
        yPrecision: Float,
        deviceId: Int,
        edgeFlags: Int,
    ): Boolean = injectTouchEvent(
        "default",
        action,
        x,
        y,
        downTime,
        eventTime,
        pressure,
        size,
        metaState,
        xPrecision,
        yPrecision,
        deviceId,
        edgeFlags
    )

    /** Shuts down the virtual display and connection for the specified agent. */
    fun shutdown(agentId: String) {
        instances.remove(agentId)?.shutdown()
    }

    fun shutdown() = shutdown("default")

    /** Key input through Shower is disabled. */
    suspend fun key(agentId: String, keyCode: Int): Boolean = false

    suspend fun keyWithMeta(agentId: String, keyCode: Int, metaState: Int): Boolean = false

    suspend fun key(keyCode: Int): Boolean = key("default", keyCode)

    suspend fun keyWithMeta(keyCode: Int, metaState: Int): Boolean = keyWithMeta("default", keyCode, metaState)
}
