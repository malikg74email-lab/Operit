package com.ai.assistance.operit.api.voice

import android.content.Context
import com.ai.assistance.operit.api.chat.llmprovider.MultiApiKeyProvider
import com.ai.assistance.operit.api.chat.llmprovider.SingleApiKeyProvider
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.PromptFunctionType
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Factory for realtime voice sessions — the sibling of [VoiceServiceFactory].
 *
 * Why not a new VoiceServiceType branch in VoiceServiceFactory: that factory creates
 * [VoiceService] instances, and the TTS-only VoiceService interface cannot express a
 * bidirectional session (plan section 1.1). [RealtimeVoiceSession] is a separate
 * abstraction, so it gets a separate factory with the same shape (create + singleton
 * keyed by config id, mirroring VoiceServiceFactory.getInstance).
 *
 * INERT BY DEFAULT: nothing in the app calls this factory yet. Building a session only
 * constructs the provider object — no socket opens and no microphone is touched until
 * the Step 5 service controller calls RealtimeVoiceSession.connect.
 *
 * Key sourcing (plan section 1.2): the API key comes from the owner's existing
 * GOOGLE / GEMINI_GENERIC ModelConfigData via [ModelConfigManager] — the same store
 * AIServiceFactory builds GeminiProvider from — so there is exactly one key store.
 * The ApiKeyProvider is built with AIServiceFactory's own rule: key pool when
 * useMultipleApiKeys is set, single key otherwise.
 */
object RealtimeVoiceServiceFactory {

    // Singleton cache, keyed by the resolved GOOGLE config id (mirrors VoiceServiceFactory).
    private var instance: GeminiLiveVoiceProvider? = null
    private var currentConfigId: String? = null

    /**
     * Builds a [GeminiLiveVoiceProvider] bound to the owner's GOOGLE/GEMINI_GENERIC
     * model config. Throws IllegalStateException when no such config exists — the
     * Live call must never silently substitute another provider's key.
     */
    suspend fun createSession(context: Context): GeminiLiveVoiceProvider {
        val appContext = context.applicationContext
        val modelConfigManager = ModelConfigManager(appContext)

        val configIds = modelConfigManager.configListFlow.first()
        var googleConfig: ModelConfigData? = null
        for (configId in configIds) {
            val candidate = modelConfigManager.getModelConfig(configId) ?: continue
            if (candidate.apiProviderType == ApiProviderType.GOOGLE ||
                candidate.apiProviderType == ApiProviderType.GEMINI_GENERIC
            ) {
                googleConfig = candidate
                break
            }
        }

        val config = googleConfig
            ?: throw IllegalStateException(
                "Gemini Live needs a Google/Gemini model config (none found in ModelConfigManager)"
            )

        val apiKeyProvider = if (config.useMultipleApiKeys) {
            MultiApiKeyProvider(config.id, modelConfigManager)
        } else {
            SingleApiKeyProvider(config.apiKey)
        }

        return GeminiLiveVoiceProvider(
            context = appContext,
            apiKeyProvider = apiKeyProvider
        )
    }

    /**
     * Singleton variant, mirroring VoiceServiceFactory.getInstance: one live-session
     * provider per GOOGLE config; a config switch discards the previous instance.
     */
    fun getInstance(context: Context): GeminiLiveVoiceProvider {
        val appContext = context.applicationContext
        val modelConfigManager = ModelConfigManager(appContext)

        // Resolved synchronously exactly the way VoiceServiceFactory resolves its profile id.
        val selectedConfigId = runBlocking {
            val configIds = modelConfigManager.configListFlow.first()
            var found: String? = null
            for (configId in configIds) {
                val candidate = modelConfigManager.getModelConfig(configId) ?: continue
                if (candidate.apiProviderType == ApiProviderType.GOOGLE ||
                    candidate.apiProviderType == ApiProviderType.GEMINI_GENERIC
                ) {
                    found = candidate.id
                    break
                }
            }
            found
        } ?: throw IllegalStateException(
            "Gemini Live needs a Google/Gemini model config (none found in ModelConfigManager)"
        )

        val existing = instance
        if (existing == null || selectedConfigId != currentConfigId) {
            existing?.disconnect()
            instance = runBlocking { createSession(appContext) }
            currentConfigId = selectedConfigId
        }
        return instance!!
    }

    /** Drops the cached instance (call when the GOOGLE config is deleted or replaced). */
    fun resetInstance() {
        instance?.disconnect()
        instance = null
        currentConfigId = null
    }

    /**
     * The persona for the Live setup frame, sourced exactly the way the chat path
     * sources it: the active character card's combined prompt.
     *
     * ConversationService builds its intro prompt as
     * CharacterCardManager.combinePrompts(activeCard.id, promptFunctionType) — here with
     * PromptFunctionType.VOICE so the card's voice-mode content (otherContentVoice, the
     * Shaheen voice rules seeded in the identity phase) is what the Live model hears.
     * The active-card lookup uses the manager's own rule (stored active id, else
     * DEFAULT_CHARACTER_CARD_ID — the same default CharacterCardManager itself applies).
     *
     * Deliberately NOT included: the full SystemPromptConfig assembly (workspace,
     * package and hook sections) is chat-request machinery owned by ConversationService;
     * if the Step 5 controller wants it in the Live systemInstruction too, it passes the
     * assembled string as RealtimeSessionConfig.systemInstruction instead of this one.
     */
    suspend fun resolveSystemInstruction(context: Context): String {
        val characterCardManager = CharacterCardManager.getInstance(context.applicationContext)
        val activeCardId = characterCardManager.observeActiveCharacterCardId().first()
            ?: CharacterCardManager.DEFAULT_CHARACTER_CARD_ID
        return characterCardManager.combinePrompts(
            activeCardId,
            promptFunctionType = PromptFunctionType.VOICE
        )
    }
}
