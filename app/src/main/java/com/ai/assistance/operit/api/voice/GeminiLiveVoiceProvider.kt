package com.ai.assistance.operit.api.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import com.ai.assistance.operit.api.chat.llmprovider.ApiKeyProvider
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini Live (BidiGenerateContent) implementation of [RealtimeVoiceSession] — Shaheen 3
 * vision phase, Step 1 (session core). No UI, nothing auto-connects: a service-scoped
 * controller (Step 5) owns instances of this class; the factory builds them inert.
 *
 * Transport and plumbing deliberately mirror the tree's existing recipes:
 * - OkHttp WebSocket client exactly as in [OpenAIRealtimeVoiceProvider]
 *   (connect 30 s, readTimeout 0, ping 20 s) and org.json frame building.
 * - Mic capture exactly as in PersonalWakeListener (AudioRecord, 16 kHz PCM16 mono),
 *   but from MediaRecorder.AudioSource.VOICE_COMMUNICATION — the SpeechService default —
 *   because a live call needs the platform's communication audio path for speakerphone
 *   barge-in. NOTE (verified gap): the tree attaches no audiofx AcousticEchoCanceler
 *   anywhere (rg verdict: zero usages), so none is invented here; whether explicit AEC
 *   attachment is needed is a Step 0 on-device question (plan risk R5).
 * - Playback streams into an [AudioTrack] (24 kHz) as parts arrive — NOT the OpenAI
 *   adapter's buffer-the-whole-utterance-then-MediaPlayer pattern.
 *
 * Tool calls are emitted on [toolCalls] and never executed here (see [LiveToolCall]).
 */
class GeminiLiveVoiceProvider(
    private val context: Context,
    private val apiKeyProvider: ApiKeyProvider
) : RealtimeVoiceSession {

    companion object {
        private const val TAG = "GeminiLiveVoiceProvider"

        /**
         * Current Gemini Live native-audio model id.
         *
         * VERIFY AT TEST TIME (Step 0): Live model naming churns between preview
         * generations; if setup is rejected, update this constant (or pass an explicit
         * modelName in RealtimeSessionConfig — the value is config, never buried in
         * frame-building strings). Kept as one named constant for exactly that reason.
         */
        const val DEFAULT_LIVE_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"

        /** Prebuilt Live voice used unless the session config overrides it. */
        const val DEFAULT_VOICE_NAME = "Kore"

        private const val LIVE_WS_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 30L
        private const val PING_INTERVAL_SECONDS = 20L

        private const val INPUT_SAMPLE_RATE = 16_000
        private const val OUTPUT_SAMPLE_RATE = 24_000

        /** 100 ms of 16 kHz audio per realtimeInput frame (plan: ~50–100 ms chunks). */
        private const val CAPTURE_CHUNK_SAMPLES = 1_600

        private const val INPUT_MIME_TYPE = "audio/pcm;rate=16000"
    }

    // Same lazy client recipe as OpenAIRealtimeVoiceProvider.
    private val webSocketClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val stateLock = Any()

    private val _sessionState = MutableStateFlow<RealtimeSessionState>(RealtimeSessionState.Ended)
    override val sessionState: StateFlow<RealtimeSessionState> = _sessionState.asStateFlow()

    private val _inputTranscripts = MutableSharedFlow<RealtimeTranscript>(extraBufferCapacity = 64)
    override val inputTranscripts: SharedFlow<RealtimeTranscript> = _inputTranscripts.asSharedFlow()

    private val _outputTranscripts = MutableSharedFlow<RealtimeTranscript>(extraBufferCapacity = 64)
    override val outputTranscripts: SharedFlow<RealtimeTranscript> = _outputTranscripts.asSharedFlow()

    private val _audioLevel = MutableStateFlow(0f)
    override val audioLevelFlow: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _toolCalls = MutableSharedFlow<LiveToolCall>(extraBufferCapacity = 32)
    override val toolCalls: SharedFlow<LiveToolCall> = _toolCalls.asSharedFlow()

    private val _toolCallCancellations = MutableSharedFlow<List<String>>(extraBufferCapacity = 16)
    override val toolCallCancellations: SharedFlow<List<String>> = _toolCallCancellations.asSharedFlow()

    private val _resumptionHandle = MutableStateFlow<String?>(null)
    override val resumptionHandleFlow: StateFlow<String?> = _resumptionHandle.asStateFlow()

    private val _usage = MutableStateFlow<RealtimeUsage?>(null)
    override val usageFlow: StateFlow<RealtimeUsage?> = _usage.asStateFlow()

    private var webSocket: WebSocket? = null
    private var sessionScope: CoroutineScope? = null
    private var currentConfig: RealtimeSessionConfig? = null

    @Volatile
    private var setupComplete = false

    @Volatile
    private var intentionalClose = false

    private val micEnabled = AtomicBoolean(true)

    // --- microphone ---
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    @Volatile
    private var captureActive = false

    // --- playback ---
    private var audioTrack: AudioTrack? = null
    private var playbackChannel: Channel<PlaybackChunk>? = null
    private var playbackJob: Job? = null
    private val playbackGeneration = AtomicInteger(0)

    private class PlaybackChunk(val generation: Int, val data: ByteArray)

    override suspend fun connect(config: RealtimeSessionConfig) {
        synchronized(stateLock) {
            when (_sessionState.value) {
                is RealtimeSessionState.Connecting,
                is RealtimeSessionState.Connected,
                is RealtimeSessionState.Listening,
                is RealtimeSessionState.Thinking,
                is RealtimeSessionState.Speaking,
                is RealtimeSessionState.Interrupted,
                is RealtimeSessionState.Paused ->
                    throw IllegalStateException("Gemini Live session is already active; disconnect it first")
                is RealtimeSessionState.Ended,
                is RealtimeSessionState.Error -> Unit
            }
        }

        currentConfig = config
        intentionalClose = false
        setupComplete = false
        micEnabled.set(true)
        _resumptionHandle.value = config.resumeHandle
        _sessionState.value = RealtimeSessionState.Connecting

        try {
            val apiKey = apiKeyProvider.getApiKey()
            if (apiKey.isBlank()) {
                throw IllegalStateException(
                    "Gemini API key is empty in the GOOGLE model config; add the key in model settings first"
                )
            }

            sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            val request = Request.Builder()
                .url("$LIVE_WS_BASE_URL?key=$apiKey")
                .build()

            val socket = webSocketClient.newWebSocket(request, liveWebSocketListener)
            synchronized(stateLock) { webSocket = socket }
        } catch (e: Exception) {
            failSession(e)
            throw e
        }
    }

    override fun disconnect() {
        synchronized(stateLock) {
            if (intentionalClose && webSocket == null &&
                _sessionState.value == RealtimeSessionState.Ended
            ) {
                return // idempotent: already fully disconnected
            }
            intentionalClose = true
        }

        teardownAudio()

        synchronized(stateLock) {
            webSocket?.close(1000, "end_call")
            webSocket = null
        }

        sessionScope?.cancel()
        sessionScope = null
        setupComplete = false
        _audioLevel.value = 0f
        _sessionState.value = RealtimeSessionState.Ended
    }

    override fun sendAudio(pcmChunk: ByteArray) {
        val socket = synchronized(stateLock) { webSocket }
        if (socket == null || !setupComplete) {
            AppLogger.d(TAG, "sendAudio dropped: session not ready (setupComplete=$setupComplete)")
            return
        }

        val frame = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("data", Base64.encodeToString(pcmChunk, Base64.NO_WRAP))
                    put("mimeType", INPUT_MIME_TYPE)
                })
            })
        }

        if (!socket.send(frame.toString())) {
            failSession(IllegalStateException("Failed to send audio frame to Gemini Live"))
        }
    }

    override fun sendToolResponse(response: LiveToolResponse) {
        sendToolResponses(listOf(response))
    }

    override fun sendToolResponses(responses: List<LiveToolResponse>) {
        val socket = synchronized(stateLock) { webSocket }
            ?: throw IllegalStateException("Cannot send tool responses: Gemini Live session is not connected")

        val functionResponses = JSONArray()
        for (response in responses) {
            functionResponses.put(JSONObject().apply {
                put("id", response.id)
                put("name", response.name)
                put("response", response.response)
            })
        }

        val frame = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", functionResponses)
            })
        }

        if (!socket.send(frame.toString())) {
            throw IllegalStateException("Failed to send toolResponse frame to Gemini Live")
        }
    }

    override fun pauseMic() {
        if (!micEnabled.getAndSet(false)) {
            return
        }

        stopCapture()

        // Tell the server the audio stream ended so it finalises the current turn.
        val socket = synchronized(stateLock) { webSocket }
        if (socket != null && setupComplete) {
            val frame = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audioStreamEnd", true)
                })
            }
            socket.send(frame.toString())
        }

        if (_sessionState.value == RealtimeSessionState.Listening ||
            _sessionState.value == RealtimeSessionState.Thinking
        ) {
            _sessionState.value = RealtimeSessionState.Paused
        }
    }

    override fun resumeMic() {
        if (micEnabled.getAndSet(true)) {
            return
        }

        if (synchronized(stateLock) { webSocket } == null || !setupComplete) {
            AppLogger.w(TAG, "resumeMic ignored: no live session to resume into")
            micEnabled.set(false)
            return
        }

        try {
            startCapture()
            _sessionState.value = RealtimeSessionState.Listening
        } catch (e: Exception) {
            failSession(e)
        }
    }

    // ------------------------------------------------------------------
    // WebSocket listener
    // ------------------------------------------------------------------

    private val liveWebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val config = currentConfig
            if (config == null) {
                failSession(IllegalStateException("Gemini Live socket opened without a session config"))
                return
            }

            if (!webSocket.send(buildSetupMessage(config).toString())) {
                failSession(IllegalStateException("Failed to send Gemini Live setup frame"))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Unparseable Gemini Live frame dropped", e)
                return
            }

            try {
                when {
                    json.has("setupComplete") -> handleSetupComplete()
                    json.has("serverContent") ->
                        handleServerContent(json.getJSONObject("serverContent"))
                    json.has("toolCall") -> handleToolCall(json.getJSONObject("toolCall"))
                    json.has("toolCallCancellation") ->
                        handleToolCallCancellation(json.getJSONObject("toolCallCancellation"))
                    json.has("sessionResumptionUpdate") ->
                        handleSessionResumptionUpdate(json.getJSONObject("sessionResumptionUpdate"))
                    json.has("goAway") -> {
                        // The server will close this connection soon. Reconnecting with
                        // resumptionHandleFlow's handle is the Step 5 controller's job;
                        // here we only record it so the drop is never a surprise.
                        val timeLeft = json.getJSONObject("goAway").optString("timeLeft")
                        AppLogger.w(TAG, "Gemini Live goAway received (timeLeft=$timeLeft); controller must reconnect with the resumption handle")
                    }
                    json.has("usageMetadata") -> {
                        val usage = json.getJSONObject("usageMetadata")
                        _usage.value = RealtimeUsage(
                            promptTokenCount = usage.optInt("promptTokenCount"),
                            responseTokenCount = usage.optInt("responseTokenCount"),
                            totalTokenCount = usage.optInt("totalTokenCount")
                        )
                    }
                    json.has("error") -> {
                        val error = json.getJSONObject("error")
                        failSession(
                            IllegalStateException(
                                "Gemini Live error ${error.optInt("code")}: ${error.optString("message")}"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                failSession(e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (intentionalClose) {
                return
            }
            val detail = response?.let { "HTTP ${it.code}" } ?: t.message ?: "unknown transport failure"
            failSession(IllegalStateException("Gemini Live transport failure: $detail", t))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (intentionalClose) {
                return
            }
            if (_sessionState.value is RealtimeSessionState.Error) {
                return // failSession already recorded the real cause
            }
            teardownAudio()
            sessionScope?.cancel()
            sessionScope = null
            setupComplete = false
            _sessionState.value = RealtimeSessionState.Ended
            AppLogger.w(TAG, "Gemini Live socket closed by server (code=$code, reason=$reason)")
        }
    }

    private fun handleSetupComplete() {
        setupComplete = true
        _sessionState.value = RealtimeSessionState.Connected

        try {
            startPlayback()
            if (micEnabled.get()) {
                startCapture()
            }
            _sessionState.value = RealtimeSessionState.Listening
        } catch (e: Exception) {
            failSession(e)
        }
    }

    private fun handleServerContent(serverContent: JSONObject) {
        // Barge-in first: the client's only job on interruption is to stop playback
        // and flush immediately (plan section 1.3.5).
        if (serverContent.optBoolean("interrupted")) {
            flushPlayback()
            _sessionState.value = RealtimeSessionState.Interrupted
        }

        val inputTranscription = serverContent.optJSONObject("inputTranscription")
        if (inputTranscription != null) {
            val transcript = RealtimeTranscript(
                text = inputTranscription.optString("text"),
                finished = inputTranscription.optBoolean("finished")
            )
            _inputTranscripts.tryEmit(transcript)
            // User is speaking -> Listening; user finished -> model owes a reply -> Thinking.
            if (_sessionState.value != RealtimeSessionState.Paused) {
                _sessionState.value =
                    if (transcript.finished) RealtimeSessionState.Thinking
                    else RealtimeSessionState.Listening
            }
        }

        val modelTurn = serverContent.optJSONObject("modelTurn")
        if (modelTurn != null) {
            val parts = modelTurn.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val inlineData = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                    val data = inlineData.optString("data")
                    if (data.isBlank()) continue
                    val pcm = Base64.decode(data, Base64.DEFAULT)
                    enqueuePlayback(pcm)
                    _sessionState.value = RealtimeSessionState.Speaking
                }
            }
        }

        val outputTranscription = serverContent.optJSONObject("outputTranscription")
        if (outputTranscription != null) {
            _outputTranscripts.tryEmit(
                RealtimeTranscript(
                    text = outputTranscription.optString("text"),
                    finished = outputTranscription.optBoolean("finished")
                )
            )
        }

        if (serverContent.optBoolean("turnComplete")) {
            if (_sessionState.value != RealtimeSessionState.Paused) {
                _sessionState.value =
                    if (micEnabled.get()) RealtimeSessionState.Listening
                    else RealtimeSessionState.Connected
            }
        }
    }

    private fun handleToolCall(toolCall: JSONObject) {
        val functionCalls = toolCall.optJSONArray("functionCalls") ?: return
        for (i in 0 until functionCalls.length()) {
            val call = functionCalls.getJSONObject(i)
            val liveCall = LiveToolCall(
                id = call.optString("id"),
                name = call.optString("name"),
                args = call.optJSONObject("args") ?: JSONObject()
            )
            // Emitted only — execution belongs to the Step 2 bridge / ToolExecutionManager.
            _toolCalls.tryEmit(liveCall)
        }
    }

    private fun handleToolCallCancellation(cancellation: JSONObject) {
        val idsArray = cancellation.optJSONArray("ids") ?: return
        val ids = mutableListOf<String>()
        for (i in 0 until idsArray.length()) {
            ids.add(idsArray.optString(i))
        }
        _toolCallCancellations.tryEmit(ids)
    }

    private fun handleSessionResumptionUpdate(update: JSONObject) {
        if (update.optBoolean("resumable")) {
            val handle = update.optString("newHandle")
            if (handle.isNotBlank()) {
                _resumptionHandle.value = handle
            }
        }
    }

    // ------------------------------------------------------------------
    // Setup frame
    // ------------------------------------------------------------------

    private fun buildSetupMessage(config: RealtimeSessionConfig): JSONObject {
        val setup = JSONObject().apply {
            put("model", "models/${config.modelName}")
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().apply { put("AUDIO") })
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", config.voiceName)
                        })
                    })
                })
            })
            if (config.systemInstruction.isNotBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", config.systemInstruction) })
                    })
                })
            }
            if (config.toolPrompts.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    put(JSONObject().apply {
                        put(
                            "functionDeclarations",
                            GeminiLiveToolDeclarations.buildFunctionDeclarations(config.toolPrompts)
                        )
                    })
                })
            }
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
            // Server VAD: this is what makes barge-in possible.
            put("realtimeInputConfig", JSONObject().apply {
                put("automaticActivityDetection", JSONObject())
            })
            // Session resumption + sliding-window compression, against the Live API's
            // ~10 min connection / ~15 min audio caps (plan risk R2).
            put("sessionResumption", JSONObject().apply {
                config.resumeHandle?.let { put("handle", it) }
            })
            put("contextWindowCompression", JSONObject().apply {
                put("slidingWindow", JSONObject())
            })
        }

        return JSONObject().apply { put("setup", setup) }
    }

    // ------------------------------------------------------------------
    // Microphone capture (mirrors PersonalWakeListener's AudioRecord recipe)
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startCapture() {
        val scope = sessionScope
            ?: throw IllegalStateException("Cannot start capture without a session scope")

        val minBufferSize = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            throw IllegalStateException("AudioRecord.getMinBufferSize failed for 16 kHz mono PCM16: $minBufferSize")
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferSize, CAPTURE_CHUNK_SAMPLES * 2 * 2)
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialise (microphone unavailable or permission missing)")
        }

        audioRecord = record
        captureActive = true
        record.startRecording()

        captureJob = scope.launch {
            val buffer = ShortArray(CAPTURE_CHUNK_SAMPLES)
            try {
                while (captureActive) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read < 0) {
                        throw IllegalStateException("AudioRecord.read failed: $read")
                    }
                    if (read == 0) continue

                    _audioLevel.value = computeRms(buffer, read)
                    sendAudio(shortsToPcmBytes(buffer, read))
                }
            } catch (e: Exception) {
                if (captureActive) {
                    failSession(e)
                }
            }
        }
    }

    private fun stopCapture() {
        captureActive = false
        captureJob?.cancel()
        captureJob = null

        val record = audioRecord
        audioRecord = null
        if (record != null) {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
            record.release()
        }

        _audioLevel.value = 0f
    }

    /** Normalised RMS (0f..1f) of PCM16 samples — same measure PersonalWakeListener gates on. */
    private fun computeRms(buffer: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sumSquares = 0.0
        for (i in 0 until count) {
            val sample = buffer[i].toDouble()
            sumSquares += sample * sample
        }
        return (Math.sqrt(sumSquares / count) / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    private fun shortsToPcmBytes(buffer: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val sample = buffer[i].toInt()
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    // ------------------------------------------------------------------
    // Playback (streaming AudioTrack at 24 kHz; never buffer-then-play)
    // ------------------------------------------------------------------

    private fun startPlayback() {
        val channel = Channel<PlaybackChunk>(Channel.UNLIMITED)
        playbackChannel = channel

        val scope = sessionScope
            ?: throw IllegalStateException("Cannot start playback without a session scope")

        playbackJob = scope.launch {
            try {
                for (chunk in channel) {
                    if (chunk.generation != playbackGeneration.get()) {
                        continue // stale audio from an interrupted turn: drop it
                    }
                    val track = audioTrack ?: createAudioTrack().also { audioTrack = it }
                    val written = track.write(chunk.data, 0, chunk.data.size, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) {
                        throw IllegalStateException("AudioTrack.write failed: $written")
                    }
                }
            } catch (e: Exception) {
                failSession(e)
            }
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            throw IllegalStateException("AudioTrack.getMinBufferSize failed for 24 kHz mono PCM16: $minBufferSize")
        }

        // Builder recipe mirrors VitsVoiceProvider.playPcm16; usage is VOICE_COMMUNICATION
        // (not USAGE_MEDIA) because this is a call, per plan section 1.3.5.
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBufferSize, OUTPUT_SAMPLE_RATE * 2 / 5))
            .build()

        track.play()
        return track
    }

    private fun enqueuePlayback(pcm: ByteArray) {
        val channel = playbackChannel ?: return
        channel.trySend(PlaybackChunk(playbackGeneration.get(), pcm))
    }

    /** Interruption: drop everything queued and flush what the track already holds. */
    private fun flushPlayback() {
        playbackGeneration.incrementAndGet()

        val track = audioTrack ?: return
        if (track.playbackState == AudioTrack.PLAYSTATE_PLAYING) {
            track.pause()
            track.flush()
            track.play()
        }
    }

    private fun stopPlayback() {
        playbackChannel?.close()
        playbackChannel = null
        playbackJob?.cancel()
        playbackJob = null

        val track = audioTrack
        audioTrack = null
        if (track != null) {
            if (track.playbackState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
            track.release()
        }
    }

    // ------------------------------------------------------------------
    // Failure handling: every error lands in the Error state with its cause
    // ------------------------------------------------------------------

    private fun teardownAudio() {
        stopCapture()
        stopPlayback()
    }

    private fun failSession(cause: Throwable) {
        val current = _sessionState.value
        if (current is RealtimeSessionState.Error) {
            return // the first cause is the recorded one
        }

        AppLogger.e(TAG, "Gemini Live session failed", cause)

        teardownAudio()

        synchronized(stateLock) {
            webSocket?.cancel()
            webSocket = null
        }

        sessionScope?.cancel()
        sessionScope = null
        setupComplete = false
        _audioLevel.value = 0f
        _sessionState.value = RealtimeSessionState.Error(cause)
    }
}
