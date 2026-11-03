package com.ai.assistance.operit.core.tools.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.llmprovider.AIService
import com.ai.assistance.operit.core.chat.hooks.toPromptTurns
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.AppListData
import com.ai.assistance.operit.core.tools.defaultTool.standard.StandardUITools
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.ShizukuAuthorizer
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.AndroidPermissionPreferences
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences
import com.ai.assistance.operit.services.FloatingChatService
import com.ai.assistance.operit.ui.common.displays.UIAutomationProgressOverlay
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ImageOutputFormat
import com.ai.assistance.operit.util.ImagePoolManager
import com.ai.assistance.operit.util.ImageRegistrationOptions
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Configuration for the PhoneAgent. */
data class AgentConfig(
    val maxSteps: Int = 20
)

/** Result of a single agent step. */
data class StepResult(
    val success: Boolean,
    val finished: Boolean,
    val action: ParsedAgentAction?,
    val thinking: String?,
    val message: String? = null
)

/** Parsed action from the model's response. */
data class ParsedAgentAction(
    val metadata: String,
    val actionName: String?,
    val fields: Map<String, String>
)

private data class PrivilegedExecutionState(
    val isAdbOrHigher: Boolean,
    val hasDebuggerShizukuAccess: Boolean
)

private fun resolvePrivilegedExecutionState(
    context: Context,
    androidPermissionPreferences: AndroidPermissionPreferences,
    checkDebuggerShizuku: Boolean = true,
    onExperimentalFlagReadError: ((Exception) -> Unit)? = null
): PrivilegedExecutionState {
    val preferredLevel = androidPermissionPreferences.getPreferredPermissionLevel()
        ?: AndroidPermissionLevel.STANDARD

    var isAdbOrHigher = when (preferredLevel) {
        AndroidPermissionLevel.DEBUGGER,
        AndroidPermissionLevel.ADMIN,
        AndroidPermissionLevel.ROOT -> true
        else -> false
    }

    if (isAdbOrHigher) {
        val experimentalEnabled = try {
            DisplayPreferencesManager.getInstance(context).isExperimentalVirtualDisplayEnabled()
        } catch (e: Exception) {
            onExperimentalFlagReadError?.invoke(e)
            true
        }
        if (!experimentalEnabled) {
            isAdbOrHigher = false
        }
    }

    val hasDebuggerShizukuAccess = if (checkDebuggerShizuku &&
        isAdbOrHigher &&
        preferredLevel == AndroidPermissionLevel.DEBUGGER
    ) {
        val isShizukuRunning = ShizukuAuthorizer.isShizukuServiceRunning()
        val hasShizukuPermission = if (isShizukuRunning) ShizukuAuthorizer.hasShizukuPermission() else false
        isShizukuRunning && hasShizukuPermission
    } else {
        true
    }

    return PrivilegedExecutionState(
        isAdbOrHigher = isAdbOrHigher,
        hasDebuggerShizukuAccess = hasDebuggerShizukuAccess
    )
}

/**
 * AI-powered agent for automating Android phone interactions.
 *
 * The agent uses a vision-language model to understand screen content
 * and decide on actions to complete user tasks.
 */
class PhoneAgent(
    private val context: Context,
    private val config: AgentConfig,
    private val uiService: AIService,
    private val actionHandler: ActionHandler,
    val agentId: String = "default",
) {
    private var _stepCount = 0
    val stepCount: Int
        get() = _stepCount

    private val _contextHistory = mutableListOf<Pair<String, String>>()
    val contextHistory: List<Pair<String, String>>
        get() = _contextHistory.toList()

    private var pauseFlow: StateFlow<Boolean>? = null

    private val requiresVirtualScreen: Boolean = agentId.isNotBlank() && agentId != "default"
    private val isMainScreenAgent: Boolean = agentId.isBlank() || agentId == "default"

    init {
        actionHandler.setAgentId(agentId)
    }

    private suspend fun awaitIfPaused() {
        val flow = pauseFlow ?: return
        if (!flow.value) {
            return
        }
        AppLogger.d("PhoneAgent", "[$agentId] awaitIfPaused: entering pause loop, delay starting")
        try {
            while (flow.value) {
                delay(200)
            }
        } finally {
            AppLogger.d("PhoneAgent", "[$agentId] awaitIfPaused: exiting pause loop")
        }
    }

    private suspend fun ensureRequiredVirtualScreenOrError(): String? {
        if (!requiresVirtualScreen) return null

        val permissionState = resolvePrivilegedExecutionState(
            context = context,
            androidPermissionPreferences = androidPermissionPreferences
        )
        if (!permissionState.isAdbOrHigher) {
            return context.getString(R.string.phone_agent_need_debug_permission)
        }

        if (!permissionState.hasDebuggerShizukuAccess) {
            return context.getString(R.string.phone_agent_shizuku_unavailable)
        }

        // The Shower virtual-display subsystem has been removed, so a dedicated
        // virtual screen can never be provided. Fail closed instead of running
        // the agent against a display that does not exist.
        return context.getString(R.string.phone_agent_virtual_screen_service_start_failed)
    }

    /**
     * Run the agent to complete a task.
     *
     * @param task Natural language description of the task.
     * @param systemPrompt System prompt for the UI automation agent.
     * @param onStep Optional callback invoked after each step with the StepResult.
     * @return Final message from the agent.
     */
    suspend fun run(
        task: String,
        systemPrompt: String,
        onStep: (suspend (StepResult) -> Unit)? = null,
        isPausedFlow: StateFlow<Boolean>? = null,
        targetApp: String? = null
    ): String {
        val floatingService = FloatingChatService.getInstance()
        val job = currentCoroutineContext()[Job]

        if (job != null) {
            PhoneAgentJobRegistry.register(agentId, job)
        } else {
            AppLogger.w("PhoneAgent", "[$agentId] run: no Job in coroutineContext, registry disabled")
        }

        val requiredVirtualScreenError = ensureRequiredVirtualScreenOrError()
        if (requiredVirtualScreenError != null) {
            return requiredVirtualScreenError
        }

        val progressOverlay = UIAutomationProgressOverlay.getInstance(context)

        val pausedMutable = isPausedFlow as? MutableStateFlow<Boolean>

        try {
            // Setup UI for agent run: hide window and show the fullscreen status indicator.
            floatingService?.setFloatingWindowVisible(false)
            useFullscreenStatusIndicatorForAgent(context, agentId)
            progressOverlay.show(
                config.maxSteps,
                context.getString(R.string.phone_agent_thinking),
                onCancel = {
                    PhoneAgentJobRegistry.cancelAgent(agentId, "User cancelled UI automation")
                    job?.cancel(CancellationException("User cancelled UI automation"))
                },
                onToggleTakeOver = { isPaused -> pausedMutable?.value = isPaused }
            )

            reset()
            _contextHistory.add("system" to systemPrompt)
            pauseFlow = isPausedFlow

            // First step with user prompt
            AppLogger.d("PhoneAgent", "[$agentId] run: starting first step for task='$task'")
            awaitIfPaused()
            var result = _executeStep(task, isFirst = true)
            val firstAction = result.action
            val firstStatusText = when {
                result.finished -> result.message ?: context.getString(R.string.phone_agent_completed)
                firstAction != null && firstAction.metadata == "do" -> {
                    val actionName = firstAction.actionName ?: ""
                    if (actionName.isNotEmpty()) context.getString(R.string.phone_agent_executing_action, actionName) else context.getString(R.string.phone_agent_executing)
                }
                else -> context.getString(R.string.phone_agent_thinking)
            }

            progressOverlay.updateProgress(stepCount, config.maxSteps, firstStatusText)

            onStep?.invoke(result)

            if (result.finished) {
                return result.message ?: "Task completed"
            }

            // Continue until finished or max steps reached
            while (_stepCount < config.maxSteps) {
                awaitIfPaused()
                result = _executeStep(null, isFirst = false)
                val action = result.action
                val statusText = when {
                    result.finished -> result.message ?: context.getString(R.string.phone_agent_completed)
                    action != null && action.metadata == "do" -> {
                        val actionName = action.actionName ?: ""
                        if (actionName.isNotEmpty()) context.getString(R.string.phone_agent_executing_action, actionName) else context.getString(R.string.phone_agent_executing)
                    }
                    else -> context.getString(R.string.phone_agent_thinking)
                }

                progressOverlay.updateProgress(stepCount, config.maxSteps, statusText)

                onStep?.invoke(result)

                if (result.finished) {
                    return result.message ?: "Task completed"
                }
            }

            return "Max steps reached"
        } finally {
            AppLogger.d("PhoneAgent", "[$agentId] run: finishing, restoring UI")
            pauseFlow = null
            floatingService?.setFloatingWindowVisible(true)
            if (isMainScreenAgent) {
                floatingService?.setStatusIndicatorVisible(false)
            } else {
                clearAgentIndicators(context, agentId)
            }
            progressOverlay.hide()
        }
    }

    /** Reset the agent state for a new task. */
    fun reset() {
        _contextHistory.clear()
        _stepCount = 0
    }

    /** Execute a single step of the agent loop. */
    private suspend fun _executeStep(userPrompt: String?, isFirst: Boolean): StepResult {
        _stepCount++
        AppLogger.d("PhoneAgent", "[$agentId] _executeStep: begin, step=$_stepCount")

        val screenshotLink = actionHandler.captureScreenshotForAgent()
        val screenInfo = buildString {
            if (screenshotLink != null) {
                appendLine("[SCREENSHOT] Below is the latest screen image:")
                appendLine(screenshotLink)
            } else {
                appendLine("No screenshot available for this step.")
            }
        }.trim()

        val userMessage = if (isFirst) {
            "$userPrompt\n\n$screenInfo"
        } else {
            "** Screen Info **\n\n$screenInfo"
        }

        _contextHistory.add("user" to userMessage)

        val responseStream = uiService.sendMessage(
            context = context,
            chatHistory = _contextHistory.toList().toPromptTurns(),
            enableThinking = false,
            stream = true,
            preserveThinkInHistory = true
        )

        val contentBuilder = StringBuilder()
        responseStream.collect { chunk -> contentBuilder.append(chunk) }
        val fullResponse = contentBuilder.toString().trim()
        AppLogger.d("PhoneAgent", "[$agentId] _executeStep: AI response collected, length=${fullResponse.length}")

        val (thinking, answer) = parseThinkingAndAction(fullResponse)
        val historyEntry = "<think>$thinking</think><answer>$answer</answer>"
        _contextHistory.add("assistant" to historyEntry)

        val parsedAction = parseAgentAction(answer)
        actionHandler.removeImagesFromLastUserMessage(_contextHistory)

        if (parsedAction.metadata == "finish") {
            val message = parsedAction.fields["message"] ?: "Task finished."
            return StepResult(success = true, finished = true, action = parsedAction, thinking = thinking, message = message)
        }

        if (parsedAction.metadata == "do") {
            awaitIfPaused()
            val execResult = actionHandler.executeAgentAction(parsedAction)
            if (execResult.shouldFinish) {
                 return StepResult(success = execResult.success, finished = true, action = parsedAction, thinking = thinking, message = execResult.message)
            }
            return StepResult(success = execResult.success, finished = false, action = parsedAction, thinking = thinking, message = execResult.message)
        }

        val errorMessage = "Unknown action format: ${parsedAction.metadata}"
        return StepResult(success = false, finished = true, action = parsedAction, thinking = thinking, message = errorMessage)
    }

    private fun extractTagContent(text: String, tag: String): String? {
        val pattern = Regex("""<$tag>(.*?)</$tag>""", RegexOption.DOT_MATCHES_ALL)
        return pattern.find(text)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun parseThinkingAndAction(content: String): Pair<String?, String> {
        val full = content.trim()
        val finishMarker = "finish(message="
        val finishIndex = full.indexOf(finishMarker)
        if (finishIndex >= 0) {
            val thinking = full.substring(0, finishIndex).trim().ifEmpty { null }
            val action = full.substring(finishIndex).trim()
            return thinking to action
        }
        val doMarker = "do(action="
        val doIndex = full.indexOf(doMarker)
        if (doIndex >= 0) {
            val thinking = full.substring(0, doIndex).trim().ifEmpty { null }
            val action = full.substring(doIndex).trim()
            return thinking to action
        }
        val thinkTag = extractTagContent(full, "think")
        val answerTag = extractTagContent(full, "answer")
        if (thinkTag != null || answerTag != null) {
            return thinkTag to (answerTag ?: full)
        }
        return null to full
    }

    private fun parseAgentAction(raw: String): ParsedAgentAction {
        val original = raw.trim()
        val finishIndex = original.lastIndexOf("finish(")
        val doIndex = original.lastIndexOf("do(")
        val startIndex = when {
            finishIndex >= 0 && doIndex >= 0 -> maxOf(finishIndex, doIndex)
            finishIndex >= 0 -> finishIndex
            doIndex >= 0 -> doIndex
            else -> -1
        }

        val trimmed = if (startIndex >= 0) original.substring(startIndex).trim() else original

        if (trimmed.startsWith("finish")) {
            val messageRegex = Regex("""finish\s*\(\s*message\s*=\s*\"(.*)\"\s*\)""", RegexOption.DOT_MATCHES_ALL)
            val message = messageRegex.find(trimmed)?.groupValues?.getOrNull(1) ?: ""
            return ParsedAgentAction(metadata = "finish", actionName = null, fields = mapOf("message" to message))
        }

        if (!trimmed.startsWith("do")) {
            return ParsedAgentAction(metadata = "unknown", actionName = null, fields = emptyMap())
        }

        val inner = trimmed.removePrefix("do").trim().removeSurrounding("(", ")")
        val fields = mutableMapOf<String, String>()
        val regex = Regex("""(\w+)\s*=\s*(?:\[(.*?)\]|\"(.*?)\"|'([^']*)'|([^,)]+))""")
        regex.findAll(inner).forEach { matchResult ->
            val key = matchResult.groupValues[1]
            val value = matchResult.groupValues.drop(2).firstOrNull { it.isNotEmpty() } ?: ""
            fields[key] = value
        }

        return ParsedAgentAction(metadata = "do", actionName = fields["action"], fields = fields)
    }
}

private suspend fun useFullscreenStatusIndicatorForAgent(context: Context, agentId: String) {
    val floatingService = FloatingChatService.getInstance()
    if (floatingService != null) {
        floatingService.setStatusIndicatorVisible(true)
    } else {
        AppLogger.d("PhoneAgent", "[$agentId] No FloatingChatService instance, using standalone rainbow border overlay")
        UIAutomationProgressOverlay.getInstance(context).setBorderEnabled(true)
    }
}

private suspend fun clearAgentIndicators(context: Context, agentId: String) {
    UIAutomationProgressOverlay.getInstance(context).setBorderEnabled(false)
    val floatingService = FloatingChatService.getInstance()
    floatingService?.setStatusIndicatorVisible(false)
}

/** Handles the execution of parsed actions. */
class ActionHandler(
    private val context: Context,
    private var screenWidth: Int,
    private var screenHeight: Int,
    private val toolImplementations: ToolImplementations
) {
    private var agentId: String = "default"
    private var appPackagesSyncedFromTool = false
    private val aiToolManager: AIToolHandler by lazy { AIToolHandler.getInstance(context) }

    fun setAgentId(id: String) {
        agentId = id
    }

    data class ActionExecResult(
        val success: Boolean,
        val shouldFinish: Boolean,
        val message: String?
    )

    companion object {
        private const val POST_LAUNCH_DELAY_MS = 1000L
        private const val POST_NON_WAIT_ACTION_DELAY_MS = 500L
    }

    private fun isMainScreenAgent(): Boolean = agentId.isBlank() || agentId == "default"

    suspend fun captureScreenshotForAgent(): String? {
        val floatingService = FloatingChatService.getInstance()
        val progressOverlay = UIAutomationProgressOverlay.getInstance(context)

        var screenshotLink: String? = null
        var dimensions: Pair<Int, Int>? = null

        try {
            // Keep screenshot captures clean: hide overlays first, then restore after capture.
            floatingService?.setStatusIndicatorVisible(false)
            progressOverlay.setOverlayVisible(false)
            delay(200)

            val screenshotTool = buildScreenshotTool()
            val (bitmap, fallbackDims) = toolImplementations.captureScreenshotBitmap(screenshotTool)

            if (bitmap != null) {
                val (compressedLink, rawDims) = saveCompressedScreenshotFromBitmap(bitmap)
                screenshotLink = compressedLink
                dimensions = fallbackDims ?: rawDims
                bitmap.recycle()
            }
        } finally {
            floatingService?.setStatusIndicatorVisible(true)
            progressOverlay.setOverlayVisible(true)
        }

        if (dimensions != null) {
            screenWidth = dimensions.first
            screenHeight = dimensions.second
        }
        return screenshotLink
    }

    private fun buildScreenshotTool(): AITool {
        return AITool(
            name = "capture_screenshot",
            parameters = emptyList()
        )
    }

    private fun saveCompressedScreenshotFromBitmap(bitmap: Bitmap): Pair<String?, Pair<Int, Int>?> {
        return try {
            val originalWidth = bitmap.width
            val originalHeight = bitmap.height
            val imageId = ImagePoolManager.addImageFromBitmap(
                bitmap = bitmap,
                mimeType = if (bitmap.hasAlpha()) "image/png" else "image/jpeg",
                options = buildScreenshotRegistrationOptions()
            )
            if (imageId == "error") {
                Pair(null, null)
            } else {
                Pair("<link type=\"image\" id=\"$imageId\"></link>", Pair(originalWidth, originalHeight))
            }
        } catch (e: Exception) {
            AppLogger.e("ActionHandler", "[$agentId] Error saving compressed screenshot", e)
            Pair(null, null)
        }
    }

    private fun buildScreenshotRegistrationOptions(): ImageRegistrationOptions {
        val prefs = DisplayPreferencesManager.getInstance(context)
        val format = prefs.getScreenshotFormat().uppercase(Locale.getDefault())
        val outputFormat =
            when (format) {
                "JPG", "JPEG" -> ImageOutputFormat.JPEG
                else -> ImageOutputFormat.PNG
            }
        return ImageRegistrationOptions(
            scalePercent = prefs.getScreenshotScalePercent().coerceIn(1, 100),
            outputFormat = outputFormat,
            jpegQuality = prefs.getScreenshotQuality().coerceIn(1, 100),
            normalizeExif = true,
            maxLongEdge = 0
        )
    }

    fun removeImagesFromLastUserMessage(history: MutableList<Pair<String, String>>) {
        val lastUserMessageIndex = history.indexOfLast { it.first == "user" }
        if (lastUserMessageIndex != -1) {
            val (role, content) = history[lastUserMessageIndex]
            if (content.contains("<link type=\"image\"")) {
                val stripped = content.replace(Regex("""<link type=\"image\".*?</link>"""), "").trim()
                history[lastUserMessageIndex] = role to stripped
            }
        }
    }

    suspend fun executeAgentAction(parsed: ParsedAgentAction): ActionExecResult {
        val actionName = parsed.actionName ?: return fail(message = "Missing action name")
        val fields = parsed.fields

        return when (actionName) {
            "Launch" -> {
                val app = fields["app"]?.takeIf { it.isNotBlank() } ?: return fail(message = "No app name specified for Launch")
                val packageName = resolveAppPackageName(app)
                try {
                    val permissionState = resolvePrivilegedExecutionState(
                        context = context,
                        androidPermissionPreferences = androidPermissionPreferences
                    )
                    if (permissionState.isAdbOrHigher && !permissionState.hasDebuggerShizukuAccess) {
                        return fail(shouldFinish = true, message = context.getString(R.string.phone_agent_shizuku_unavailable))
                    }

                    val result = aiToolManager.executeTool(
                        AITool("start_app", listOf(ToolParameter("package_name", packageName)))
                    )
                    if (result.success) {
                        delay(POST_LAUNCH_DELAY_MS)
                        ok()
                    } else {
                        fail(message = result.error ?: "Failed to launch app: $packageName")
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    fail(message = "Exception while launching app: ${e.message}")
                }
            }
            "Tap" -> {
                val element = fields["element"] ?: return fail(message = "No element for Tap")
                val (x, y) = parseRelativePoint(element) ?: return fail(message = "Invalid coordinates for Tap: $element")
                val exec = withAgentUiHiddenForAction {
                    val params = listOf(ToolParameter("x", x.toString()), ToolParameter("y", y.toString()))
                    val result = toolImplementations.tap(AITool("tap", params))
                    if (result.success) ok() else fail(message = result.error ?: "Tap failed")
                }
                if (exec.success && !exec.shouldFinish) delay(POST_NON_WAIT_ACTION_DELAY_MS)
                exec
            }
            "Type" -> {
                val text = fields["text"] ?: ""
                val exec = withAgentUiHiddenForAction {
                    val params = listOf(ToolParameter("text", text))
                    val result = toolImplementations.setInputText(AITool("set_input_text", params))
                    if (result.success) ok() else fail(message = result.error ?: "Type failed")
                }
                if (exec.success && !exec.shouldFinish) delay(POST_NON_WAIT_ACTION_DELAY_MS)
                exec
            }
            "Swipe" -> {
                val start = fields["start"] ?: return fail(message = "Missing swipe start")
                val end = fields["end"] ?: return fail(message = "Missing swipe end")
                val (sx, sy) = parseRelativePoint(start) ?: return fail(message = "Invalid swipe start")
                val (ex, ey) = parseRelativePoint(end) ?: return fail(message = "Invalid swipe end")
                val exec = withAgentUiHiddenForAction {
                    val params = listOf(
                        ToolParameter("start_x", sx.toString()), ToolParameter("start_y", sy.toString()),
                        ToolParameter("end_x", ex.toString()), ToolParameter("end_y", ey.toString())
                    )
                    val result = toolImplementations.swipe(AITool("swipe", params))
                    if (result.success) ok() else fail(message = result.error ?: "Swipe failed")
                }
                if (exec.success && !exec.shouldFinish) delay(POST_NON_WAIT_ACTION_DELAY_MS)
                exec
            }
            "Back" -> {
                val exec = withAgentUiHiddenForAction {
                    val params = listOf(ToolParameter("key_code", "KEYCODE_BACK"))
                    val result = toolImplementations.pressKey(AITool("press_key", params))
                    if (result.success) ok() else fail(message = result.error ?: "Back failed")
                }
                if (exec.success && !exec.shouldFinish) delay(POST_NON_WAIT_ACTION_DELAY_MS)
                exec
            }
            "Home" -> {
                val exec = withAgentUiHiddenForAction {
                    val params = listOf(ToolParameter("key_code", "KEYCODE_HOME"))
                    val result = toolImplementations.pressKey(AITool("press_key", params))
                    if (result.success) ok() else fail(message = result.error ?: "Home failed")
                }
                if (exec.success && !exec.shouldFinish) delay(POST_NON_WAIT_ACTION_DELAY_MS)
                exec
            }
            "Wait" -> {
                val seconds = fields["duration"]?.replace("seconds", "")?.trim()?.toDoubleOrNull() ?: 1.0
                delay((seconds * 1000).toLong().coerceAtLeast(0L))
                ok()
            }
            "Take_over" -> ok(shouldFinish = true, message = fields["message"] ?: "User takeover required")
            else -> fail(message = "Unknown action: $actionName")
        }
    }

    private suspend fun withAgentUiHiddenForAction(
        block: suspend () -> ActionExecResult
    ): ActionExecResult {
        val floatingService = FloatingChatService.getInstance()
        val progressOverlay = UIAutomationProgressOverlay.getInstance(context)
        try {
            if (isMainScreenAgent()) {
                floatingService?.setStatusIndicatorVisible(false)
            }
            progressOverlay.setOverlayVisible(false)
            delay(200)
            return block()
        } finally {
            if (isMainScreenAgent()) {
                floatingService?.setStatusIndicatorVisible(true)
            }
            progressOverlay.setOverlayVisible(true)
        }
    }

    private fun ok(shouldFinish: Boolean = false, message: String? = null) = ActionExecResult(true, shouldFinish, message)
    private fun fail(shouldFinish: Boolean = false, message: String) = ActionExecResult(false, shouldFinish, message)

    private fun parseRelativePoint(value: String): Pair<Int, Int>? {
        val parts = value.trim().removeSurrounding("[", "]").split(",").map { it.trim() }
        if (parts.size < 2) return null
        val relX = parts[0].toIntOrNull() ?: return null
        val relY = parts[1].toIntOrNull() ?: return null
        return (relX / 1000.0 * screenWidth).toInt() to (relY / 1000.0 * screenHeight).toInt()
    }

    private suspend fun resolveAppPackageName(app: String): String {
        val trimmed = app.trim()
        val lowered = trimmed.lowercase(Locale.getDefault())
        fun lookup(): String? =
            StandardUITools.APP_PACKAGES[app] ?: StandardUITools.APP_PACKAGES[trimmed] ?: StandardUITools.APP_PACKAGES[lowered]

        lookup()?.let { return it }

        syncAppPackagesFromToolIfNeeded()
        return lookup() ?: trimmed
    }

    private suspend fun syncAppPackagesFromToolIfNeeded() {
        if (appPackagesSyncedFromTool) return
        appPackagesSyncedFromTool = true

        val listResult = aiToolManager.executeTool(AITool("list_installed_apps"))
        if (!listResult.success) {
            AppLogger.w("PhoneAgent", "[$agentId] Failed to sync app packages from tool layer: ${listResult.error}")
            return
        }

        val appListData = listResult.result as? AppListData ?: return
        val discoveredPackages = mutableMapOf<String, String>()

        appListData.packages.forEach { entry ->
            val parsed = parseToolAppEntry(entry) ?: return@forEach
            val (appName, packageName) = parsed
            if (appName.isBlank() || packageName.isBlank()) return@forEach

            discoveredPackages.putIfAbsent(appName, packageName)
            discoveredPackages.putIfAbsent(appName.lowercase(Locale.getDefault()), packageName)
        }

        if (discoveredPackages.isNotEmpty()) {
            StandardUITools.addAppPackages(discoveredPackages)
        }
    }

    private fun parseToolAppEntry(entry: String): Pair<String, String>? {
        val left = entry.lastIndexOf('(')
        val right = entry.lastIndexOf(')')
        if (left < 0 || right <= left) return null

        val appName = entry.substring(0, left).trim()
        val packageName = entry.substring(left + 1, right).trim()
        if (packageName.isBlank()) return null
        return Pair(if (appName.isBlank()) packageName else appName, packageName)
    }
}

/** Interface for providing tool implementations to the ActionHandler. */
interface ToolImplementations {
    suspend fun tap(tool: AITool): ToolResult
    suspend fun longPress(tool: AITool): ToolResult
    suspend fun setInputText(tool: AITool): ToolResult
    suspend fun swipe(tool: AITool): ToolResult
    suspend fun pressKey(tool: AITool): ToolResult
    suspend fun captureScreenshot(tool: AITool): Pair<String?, Pair<Int, Int>?>
    suspend fun captureScreenshotBitmap(tool: AITool): Pair<Bitmap?, Pair<Int, Int>?> {
        val (filePath, dimensions) = captureScreenshot(tool)
        if (filePath == null) {
            return Pair(null, dimensions)
        }

        val bitmap = BitmapFactory.decodeFile(filePath) ?: return Pair(null, dimensions)
        val resolvedDimensions = dimensions ?: Pair(bitmap.width, bitmap.height)
        return Pair(bitmap, resolvedDimensions)
    }
}
