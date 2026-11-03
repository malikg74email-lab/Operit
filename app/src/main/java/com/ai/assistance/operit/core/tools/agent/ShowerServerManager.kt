package com.ai.assistance.operit.core.tools.agent

import android.content.Context
import com.ai.assistance.showerclient.ShowerServerManager as CoreShowerServerManager

/**
 * Helper to manage the lifecycle of the Shower server (shower-server.jar) on the device.
 *
 * The jar is packaged in app assets (src/main/assets/shower-server.jar).
 * At runtime we copy it to the app's files directory and start it via app_process:
 *   CLASSPATH=/data/user/<current_user_id>/<pkg>/files/shower-server.jar app_process / com.ai.assistance.shower.Main
 */
object ShowerServerManager {

    /**
     * Wave 4, step 1: starting the Shower server is disabled at this facade.
     * The underlying manager remains linked only until the follow-up deletion
     * step; this entry point must never delegate a start request to it.
     */
    suspend fun ensureServerStarted(context: Context): Boolean = false

    /**
     * Stop the Shower server process if running.
     */
    suspend fun stopServer(): Boolean {
        return CoreShowerServerManager.stopServer()
    }
}
