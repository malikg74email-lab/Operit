package com.ai.assistance.operit.api.voice

import com.ai.assistance.operit.data.model.ToolPrompt
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * State of a bidirectional realtime voice session (Shaheen 3 vision phase, Step 1).
 *
 * The orb UI (Step 6) maps these 1:1: Connecting folds into the preview's "Waking",
 * Listening / Thinking / Speaking keep their names, Ended is the resting state.
 * Error always carries the cause — a session must never fail silently.
 */
sealed class RealtimeSessionState {
    /** No live session (never connected, or cleanly ended via [RealtimeVoiceSession.disconnect]). */
    object Ended : RealtimeSessionState()

    /** WebSocket opened, setup frame sent, waiting for setupComplete. */
    object Connecting : RealtimeSessionState()

    /** Setup accepted; session is live but no audio is flowing yet. */
    object Connected : RealtimeSessionState()

    /** Mic is streaming and the user may speak (also the resting state between turns). */
    object Listening : RealtimeSessionState()

    /** User turn finished (input transcription finished), model has not started replying yet. */
    object Thinking : RealtimeSessionState()

    /** Model audio is playing back. */
    object Speaking : RealtimeSessionState()

    /** The user barged in: playback was flushed. Next transcript/model event moves the state on. */
    object Interrupted : RealtimeSessionState()

    /** Mic paused by the owner (Pause Call); the session itself stays connected. */
    object Paused : RealtimeSessionState()

    /** Terminal failure. [cause] is the recorded reason (network, audio device, protocol). */
    data class Error(val cause: Throwable) : RealtimeSessionState()
}

/** One transcription event from the session. [finished] marks the end of that utterance/reply. */
data class RealtimeTranscript(
    val text: String,
    val finished: Boolean
)

/**
 * A function call requested by the live model. The session NEVER executes these itself:
 * they are emitted on [RealtimeVoiceSession.toolCalls] for the tools bridge (Step 2), which
 * routes them through ToolExecutionManager so permission gating and verify-after-act apply
 * exactly as they do for chat-originated calls.
 */
data class LiveToolCall(
    val id: String,
    val name: String,
    val args: JSONObject
)

/** The bridge's answer to a [LiveToolCall], returned via [RealtimeVoiceSession.sendToolResponse]. */
data class LiveToolResponse(
    val id: String,
    val name: String,
    val response: JSONObject
)

/** Token usage of the live session, surfaced so data/token burn stays visible to the owner. */
data class RealtimeUsage(
    val promptTokenCount: Int,
    val responseTokenCount: Int,
    val totalTokenCount: Int
)

/**
 * Everything [RealtimeVoiceSession.connect] needs.
 *
 * [systemInstruction] is supplied by the caller: the chat-grade assembly lives in
 * ConversationService / SystemPromptConfig (workspace, packages, hooks), which a voice
 * provider must not re-implement. RealtimeVoiceServiceFactory.resolveSystemInstruction
 * sources the persona core the same way the chat path does (active character card via
 * CharacterCardManager.combinePrompts with PromptFunctionType.VOICE).
 *
 * [toolPrompts] is the same List<ToolPrompt> the chat path hands to its providers
 * (SystemToolPrompts categories + role-card filtering, see EnhancedAIService); the
 * provider converts them to Live functionDeclarations via [GeminiLiveToolDeclarations].
 */
data class RealtimeSessionConfig(
    val systemInstruction: String,
    val toolPrompts: List<ToolPrompt> = emptyList(),
    val modelName: String = GeminiLiveVoiceProvider.DEFAULT_LIVE_MODEL,
    val voiceName: String = GeminiLiveVoiceProvider.DEFAULT_VOICE_NAME,
    val resumeHandle: String? = null
)

/**
 * A bidirectional realtime voice session — the sibling abstraction the TTS-only
 * [VoiceService] cannot express (no mic input, no event stream, no session concept).
 *
 * Implementations own the transport and the audio devices for the duration of a call;
 * ownership of the session itself belongs to a service-scoped controller (Step 5),
 * never to a UI view model.
 */
interface RealtimeVoiceSession {
    /** Current session state; the single source of truth for orb / mini-orb / notification. */
    val sessionState: StateFlow<RealtimeSessionState>

    /** Transcriptions of what the user said (server-side input transcription). */
    val inputTranscripts: SharedFlow<RealtimeTranscript>

    /** Transcriptions of what the model said (server-side output transcription). */
    val outputTranscripts: SharedFlow<RealtimeTranscript>

    /** Normalised microphone level (0f..1f) while capturing; drives the orb's reactivity. */
    val audioLevelFlow: StateFlow<Float>

    /** Function calls requested by the model. Emitted, never executed, by the session. */
    val toolCalls: SharedFlow<LiveToolCall>

    /** Ids of tool calls the server cancelled (interrupted turn) — the bridge drops those. */
    val toolCallCancellations: SharedFlow<List<String>>

    /** Latest resumable-session handle from the server; persist it to survive reconnects. */
    val resumptionHandleFlow: StateFlow<String?>

    /** Latest token-usage snapshot from the server, or null before the first one arrives. */
    val usageFlow: StateFlow<RealtimeUsage?>

    /**
     * Opens the transport, sends the setup frame and, once the server accepts it,
     * starts mic capture and playback. Throws after recording the failure in
     * [sessionState] when the session cannot be established at all (e.g. no API key).
     */
    suspend fun connect(config: RealtimeSessionConfig)

    /**
     * Sends one raw PCM16 chunk (16 kHz mono) to the session. The provider's own capture
     * loop uses this same egress path; it is public so the Step 0 test harness can feed
     * audio without a microphone. Chunks sent while not connected are dropped and logged.
     */
    fun sendAudio(pcmChunk: ByteArray)

    /** Returns one tool result to the model. Throws IllegalStateException if not connected. */
    fun sendToolResponse(response: LiveToolResponse)

    /** Returns several tool results in a single toolResponse frame. */
    fun sendToolResponses(responses: List<LiveToolResponse>)

    /** Pause Call semantics: stop capturing and close the audio stream; session stays up. */
    fun pauseMic()

    /** Undoes [pauseMic]: capture restarts and the state returns to Listening. */
    fun resumeMic()

    /** Ends the call and releases socket, microphone and playback. Idempotent. */
    fun disconnect()
}
