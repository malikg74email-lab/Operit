package com.ai.assistance.operit.api.voice

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.climode.ToolExposureMode
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.StreamCollector
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Step 2 of the Shaheen 3 vision phase: the bridge between a [RealtimeVoiceSession]'s
 * tool calls and the chat path's tool execution.
 *
 * The session only EMITS [LiveToolCall]s (see RealtimeVoiceSession.toolCalls); this
 * bridge executes them through [ToolExecutionManager.executeInvocations] — the exact
 * entry point EnhancedAIService.handleToolInvocation uses — so a voice-initiated call
 * passes the same hook interception, the same Allow/Ask/Deny permission check
 * (ToolExecutionManager.checkToolPermission) and the same verify-after-act wrapper
 * (AIToolHandler.registerTool wraps the mutating UI tools at registration) as a
 * chat-initiated call. There is deliberately no second execution route: a tool the
 * owner would have to approve in chat asks for approval here too.
 *
 * Answers go back through RealtimeVoiceSession.sendToolResponse, one frame per call,
 * as each execution completes: the session interface exposes no turn-boundary signal
 * to batch by, and the provider's single-response frame is a first-class shape
 * (sendToolResponse wraps a one-element functionResponses array). Every call that is
 * not cancelled is answered exactly once — a Live turn waits for its function
 * responses, so an unanswered call would hang the conversation. Failures are answered
 * honestly as failures (see [toLiveResponsePayload]); nothing is silently dropped.
 *
 * Execution is serialised through one mutex, in arrival order. The chat path runs
 * one batch at a time per conversation, and mutating UI tools (tap, click, type)
 * must not race each other from parallel voice jobs; read-only parallelism inside a
 * batch is ToolExecutionManager's own business and a voice turn's calls are
 * independent single-call batches here.
 *
 * Cancellation (session toolCallCancellations): the cancelled id is recorded first,
 * then its job is cancelled. Cancelling a queued call prevents it from executing;
 * cancelling a running call cancels the executeInvocations coroutine (structured
 * concurrency propagates into its children), and the post-execution re-check drops
 * any result that still slips through — a cancelled call is NEVER answered late.
 * Limit, recorded honestly: a tool blocked inside a non-cooperative blocking call
 * cannot be interrupted mid-flight; cancellation then only guarantees the bridge
 * stops waiting and discards the late result.
 *
 * Lifecycle: INERT until [attach]. The Step 5 service controller creates the bridge,
 * calls [attach] with the connected session and [detach] when the call ends. The
 * bridge also detaches itself when the session reaches Ended or Error, so a dead
 * session leaks no collectors or execution jobs.
 */
class LiveToolBridge(
    context: Context,
    private val callerName: String? = null,
    private val callerChatId: String? = null,
    private val callerCardId: String? = null
) {
    companion object {
        private const val TAG = "LiveToolBridge"
    }

    private val appContext: Context = context.applicationContext

    // The same singletons, obtained the same way EnhancedAIService obtains them.
    private val toolHandler: AIToolHandler = AIToolHandler.getInstance(appContext)
    private val packageManager: PackageManager = PackageManager.getInstance(appContext, toolHandler)

    private val stateLock = Any()
    private var bridgeScope: CoroutineScope? = null

    /** Serialises tool execution across calls (see class KDoc). */
    private val executionMutex = Mutex()

    /** In-flight execution jobs by Live call id, for cancellation. */
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Ids the server cancelled; checked before executing and again before answering. */
    private val cancelledCallIds: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * executeInvocations also publishes every result as chat markup (the UI/history
     * channel). The voice path consumes ToolExecutionBatch.results instead, so the
     * markup is deliberately not forwarded anywhere — there is no chat UI in a call.
     */
    private val markupCollector = object : StreamCollector<String> {
        override suspend fun emit(value: String) {
            AppLogger.d(TAG, "Live tool result markup not forwarded (${value.length} chars)")
        }
    }

    /**
     * Starts collecting [session]'s tool calls and cancellations. Any previously
     * attached session is detached first. Safe to call again after [detach].
     */
    fun attach(session: RealtimeVoiceSession) {
        detach()

        // Same scope recipe as GeminiLiveVoiceProvider's session scope.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        synchronized(stateLock) {
            bridgeScope = scope
        }

        scope.launch {
            session.toolCalls.collect { call ->
                if (cancelledCallIds.contains(call.id)) {
                    AppLogger.d(TAG, "Dropping tool call ${call.name} (${call.id}): already cancelled")
                    return@collect
                }
                val job = scope.launch { executeAndAnswer(session, call) }
                jobs[call.id] = job
                job.invokeOnCompletion { jobs.remove(call.id, job) }
            }
        }

        scope.launch {
            session.toolCallCancellations.collect { ids ->
                for (id in ids) {
                    // Record first, cancel second: a job that starts or finishes after
                    // this point still sees the id in the set and drops its result.
                    cancelledCallIds.add(id)
                    jobs[id]?.cancel()
                    AppLogger.d(TAG, "Tool call cancelled by server: $id")
                }
            }
        }

        scope.launch {
            session.sessionState.collect { state ->
                if (state is RealtimeSessionState.Ended || state is RealtimeSessionState.Error) {
                    detach()
                }
            }
        }
    }

    /**
     * Stops the bridge: cancels every in-flight execution and all collectors.
     * Idempotent. Synchronous on purpose — it is called from inside the bridge's
     * own session-state collector when a session ends, so it must not suspend.
     */
    fun detach() {
        val scope = synchronized(stateLock) {
            val current = bridgeScope
            bridgeScope = null
            current
        }
        scope?.cancel()
        jobs.clear()
        cancelledCallIds.clear()
    }

    private suspend fun executeAndAnswer(session: RealtimeVoiceSession, call: LiveToolCall) {
        if (cancelledCallIds.contains(call.id)) {
            AppLogger.d(TAG, "Not executing cancelled tool call ${call.name} (${call.id})")
            return
        }

        val result: ToolResult = try {
            executionMutex.withLock {
                if (cancelledCallIds.contains(call.id)) {
                    AppLogger.d(TAG, "Tool call ${call.name} (${call.id}) cancelled while queued")
                    return
                }
                val batch = ToolExecutionManager.executeInvocations(
                    invocations = listOf(toInvocation(call)),
                    context = appContext,
                    toolHandler = toolHandler,
                    packageManager = packageManager,
                    collector = markupCollector,
                    // The chat path computes this from the model config's provider
                    // type; for the GOOGLE Live session that computation yields FULL.
                    toolExposureMode = ToolExposureMode.resolve(ApiProviderType.GOOGLE),
                    callerName = callerName,
                    callerChatId = callerChatId,
                    callerCardId = callerCardId
                )
                // One invocation in, exactly one result out (OrderedToolResults
                // completes every index or throws). A missing result is a bridge-level
                // anomaly, surfaced as an honest error answer below via the throw.
                batch.results.firstOrNull()
                    ?: throw IllegalStateException(
                        "ToolExecutionManager returned no result for ${call.name} (${call.id})"
                    )
            }
        } catch (e: CancellationException) {
            // Cancelled by the server (or by detach): drop the call, never answer it.
            AppLogger.d(TAG, "Tool execution cancelled: ${call.name} (${call.id})")
            throw e
        } catch (e: Exception) {
            // Tool-level failures never reach here — they come back as ToolResults.
            // This is a bridge/manager-level failure; the model must still get an
            // answer or its turn hangs, so answer with the failure itself, verbatim.
            AppLogger.e(TAG, "Tool execution failed for ${call.name} (${call.id})", e)
            answer(session, call, errorPayload("Tool execution failed: ${e.message ?: "unknown error"}"))
            return
        }

        // The cancellation may have landed while the (uncancellable tail of the) tool
        // was finishing: re-check before answering so a cancelled call is never
        // answered late.
        if (cancelledCallIds.contains(call.id) || !currentCoroutineContext().isActive) {
            AppLogger.d(TAG, "Dropping late result for cancelled tool call ${call.name} (${call.id})")
            return
        }

        answer(session, call, toLiveResponsePayload(result))
    }

    /** Sends one answer; a session that ended mid-execution makes this a logged no-op. */
    private fun answer(session: RealtimeVoiceSession, call: LiveToolCall, response: JSONObject) {
        try {
            session.sendToolResponse(
                LiveToolResponse(id = call.id, name = call.name, response = response)
            )
        } catch (e: Exception) {
            // sendToolResponse throws IllegalStateException when the session is no
            // longer connected: the call dies with its session. Log it — the loss
            // must never be silent — but there is nowhere left to deliver it.
            AppLogger.w(
                TAG,
                "Tool response for ${call.name} (${call.id}) could not be delivered; session not connected",
                e
            )
        }
    }

    /**
     * LiveToolCall -> the chat path's invocation model.
     *
     * Argument conversion mirrors ToolExecutionManager.resolveProxyParameters — the
     * tree's own JSONObject -> List<ToolParameter> conversion (it is private, so it
     * is reproduced here exactly): strings stay as-is, null becomes the literal
     * "null", and every other value (numbers, booleans, nested objects/arrays)
     * becomes its JSON text. AITool parameters are strings by design.
     *
     * rawText: the chat path stores the model's call markup there, and
     * ToolExecutionManager.checkToolPermission only scans it for the deny_tool tag.
     * The faithful Live analogue is the call's own JSON, so that scan behaves the
     * same. responseLocation has no meaning without a text response; IntRange.EMPTY
     * is safe because its only other consumer (AIToolHandler.replaceToolInvocation)
     * has no call sites.
     */
    private fun toInvocation(call: LiveToolCall): ToolInvocation {
        val parameters = mutableListOf<ToolParameter>()
        val keys = call.args.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = call.args.opt(key)
            val valueString = when (value) {
                null, JSONObject.NULL -> "null"
                is String -> value
                else -> value.toString()
            }
            parameters.add(ToolParameter(name = key, value = valueString))
        }

        val rawText = JSONObject().apply {
            put("name", call.name)
            put("args", call.args)
        }.toString()

        return ToolInvocation(
            tool = AITool(name = call.name, parameters = parameters),
            rawText = rawText,
            responseLocation = IntRange.EMPTY
        )
    }

    /**
     * ToolResult -> the functionResponse payload the voice model hears.
     *
     * Success uses the tree's Gemini shape (GeminiProvider builds functionResponse
     * responses as a single "result" entry holding the result text). Failure uses
     * the Gemini function-calling "error" entry, with the payload composed exactly
     * as ConversationMarkupManager.formatToolResultForMessage composes the error
     * content the chat model sees: the error message first, then any result detail.
     * The voice model is told the call failed — error prose is never dressed up as
     * a successful result.
     */
    private fun toLiveResponsePayload(result: ToolResult): JSONObject {
        if (result.success) {
            return JSONObject().apply {
                put("result", result.result.toString())
            }
        }

        val payload = buildString {
            val message = result.error.orEmpty().trim()
            val detail = result.result.toString().trim()
            append(message)
            if (detail.isNotEmpty()) {
                if (message.isNotEmpty()) {
                    append("\n\n")
                }
                append(detail)
            }
        }
        return errorPayload(payload)
    }

    private fun errorPayload(message: String): JSONObject {
        return JSONObject().apply {
            put("error", message)
        }
    }
}
