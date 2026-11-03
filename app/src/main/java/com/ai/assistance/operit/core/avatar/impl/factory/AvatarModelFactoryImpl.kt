package com.ai.assistance.operit.core.avatar.impl.factory

import com.ai.assistance.operit.core.avatar.common.factory.AvatarModelFactory
import com.ai.assistance.operit.core.avatar.common.model.AvatarModel
import com.ai.assistance.operit.core.avatar.common.model.AvatarType
import com.ai.assistance.operit.core.avatar.common.state.AvatarEmotion
import com.ai.assistance.operit.core.avatar.impl.mp4.model.Mp4AvatarModel
import com.ai.assistance.operit.core.avatar.impl.webp.model.WebPAvatarModel
import com.ai.assistance.operit.data.model.DragonBonesModel
import java.io.File

class AvatarModelFactoryImpl : AvatarModelFactory {

    override fun createModel(
        id: String,
        name: String,
        type: AvatarType,
        data: Map<String, Any>
    ): AvatarModel? {
        return when (type) {
            AvatarType.DRAGONBONES -> createWebPModel(id, name, dragonBonesDataAsWebPData(data))
            AvatarType.WEBP -> createWebPModel(id, name, data)
            AvatarType.MP4 -> createMp4Model(id, name, data)
            AvatarType.MMD -> createMp4Model(id, name, data)
            AvatarType.GLTF -> createMp4Model(id, name, data)
            AvatarType.FBX -> createMp4Model(id, name, data)
        }
    }

    override fun createModelFromData(dataModel: Any): AvatarModel? {
        return when (dataModel) {
            is DragonBonesModel -> {
                WebPAvatarModel(
                    id = dataModel.id,
                    name = dataModel.name,
                    basePath = dataModel.folderPath,
                    emotionToFileMap = mapOf(AvatarEmotion.IDLE to dataModel.textureImageFile),
                    availableFiles = listOf(dataModel.textureImageFile),
                    currentEmotion = AvatarEmotion.IDLE
                )
            }
            else -> {
                if (dataModel is Map<*, *>) {
                    val dataMap = dataModel as? Map<String, Any> ?: return null
                    val id = dataMap["id"] as? String ?: return null
                    val name = dataMap["name"] as? String ?: return null
                    val typeStr = dataMap["type"] as? String ?: return null
                    val type = try {
                        AvatarType.valueOf(typeStr)
                    } catch (e: IllegalArgumentException) {
                        return null
                    }
                    return createModel(id, name, type, dataMap)
                }
                null
            }
        }
    }

    override fun createDefaultModel(type: AvatarType, baseName: String): AvatarModel? {
        return when (type) {
            AvatarType.DRAGONBONES -> {
                val defaultData = mapOf(
                    "folderPath" to "assets/avatars/default",
                    "skeletonFile" to "default_ske.json",
                    "textureJsonFile" to "default_tex.json",
                    "textureImageFile" to "default_tex.png",
                    "isBuiltIn" to true
                )
                createWebPModel("default_dragonbones", baseName, dragonBonesDataAsWebPData(defaultData))
            }
            AvatarType.WEBP -> {
                WebPAvatarModel.createStandard(
                    id = "default_webp",
                    name = baseName,
                    basePath = "assets/avatars/default"
                )
            }
            AvatarType.MP4 -> {
                Mp4AvatarModel.createStandard(
                    id = "default_mp4",
                    name = baseName,
                    basePath = "assets/avatars/default"
                )
            }
            AvatarType.MMD -> {
                val defaultData = mapOf(
                    "basePath" to "assets/avatars/default",
                    "modelFile" to "default.pmx"
                )
                createMp4Model("default_mmd", baseName, defaultData)
            }
            AvatarType.GLTF -> {
                val defaultData = mapOf(
                    "basePath" to "assets/avatars/default",
                    "modelFile" to "default.glb"
                )
                createMp4Model("default_gltf", baseName, defaultData)
            }
            AvatarType.FBX -> {
                val defaultData = mapOf(
                    "basePath" to "assets/avatars/default",
                    "modelFile" to "default.fbx"
                )
                createMp4Model("default_fbx", baseName, defaultData)
            }
        }
    }

    override fun validateData(type: AvatarType, data: Map<String, Any>): Boolean {
        val requiredKeys = getRequiredDataKeys(type)
        return requiredKeys.all { key -> data.containsKey(key) && data[key] != null }
    }

    override val supportedTypes: List<AvatarType>
        get() = listOf(
            AvatarType.WEBP,
            AvatarType.MP4
        )

    override fun getRequiredDataKeys(type: AvatarType): List<String> {
        return when (type) {
            AvatarType.DRAGONBONES -> listOf(
                "folderPath",
                "skeletonFile",
                "textureJsonFile",
                "textureImageFile"
            )
            AvatarType.WEBP -> listOf("basePath")
            AvatarType.MP4 -> listOf("basePath")
            AvatarType.MMD -> listOf("basePath", "modelFile")
            AvatarType.GLTF -> listOf("basePath", "modelFile")
            AvatarType.FBX -> listOf("basePath", "modelFile")
        }
    }

    private fun dragonBonesDataAsWebPData(data: Map<String, Any>): Map<String, Any> {
        val basePath = data["basePath"] as? String
            ?: data["folderPath"] as? String
            ?: return data

        val converted = data.toMutableMap()
        converted["basePath"] = basePath

        val textureImageFile = data["textureImageFile"] as? String
        if (!textureImageFile.isNullOrBlank() &&
            data["webpFiles"] == null &&
            data["emotionToFileMap"] == null
        ) {
            converted["emotionToFileMap"] = mapOf(AvatarEmotion.IDLE.name to textureImageFile)
        }
        return converted
    }

    private fun createWebPModel(id: String, name: String, data: Map<String, Any>): AvatarModel? {
        return try {
            val basePath = data["basePath"] as? String ?: return null
            val availableFiles = extractAvailableMediaFiles(data, "webpFiles", setOf("webp"))
            val emotionMap =
                parseExplicitEmotionMap(data["emotionToFileMap"] as? Map<*, *>)
                    .ifEmpty { inferEmotionToMediaFileMap(availableFiles) }
                    .ifEmpty { fallbackEmotionMap(availableFiles) }

            if (emotionMap.isNotEmpty() || availableFiles.isNotEmpty()) {
                WebPAvatarModel(
                    id = id,
                    name = name,
                    basePath = basePath,
                    emotionToFileMap = emotionMap,
                    availableFiles = if (availableFiles.isNotEmpty()) availableFiles else emotionMap.values.toList(),
                    currentEmotion = parseCurrentEmotion(data)
                )
            } else {
                WebPAvatarModel.createStandard(
                    id = id,
                    name = name,
                    basePath = basePath
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun createMp4Model(id: String, name: String, data: Map<String, Any>): AvatarModel? {
        return try {
            val basePath = data["basePath"] as? String ?: return null
            val availableFiles = extractAvailableMediaFiles(data, "mp4Files", setOf("mp4"))
            val emotionMap =
                parseExplicitEmotionMap(data["emotionToFileMap"] as? Map<*, *>)
                    .ifEmpty { inferEmotionToMediaFileMap(availableFiles) }
                    .ifEmpty { fallbackEmotionMap(availableFiles) }

            if (emotionMap.isNotEmpty() || availableFiles.isNotEmpty()) {
                Mp4AvatarModel(
                    id = id,
                    name = name,
                    basePath = basePath,
                    emotionToFileMap = emotionMap,
                    availableFiles = if (availableFiles.isNotEmpty()) availableFiles else emotionMap.values.toList(),
                    currentEmotion = parseCurrentEmotion(data)
                )
            } else {
                Mp4AvatarModel.createStandard(
                    id = id,
                    name = name,
                    basePath = basePath
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseExplicitEmotionMap(raw: Map<*, *>?): Map<AvatarEmotion, String> {
        if (raw == null) {
            return emptyMap()
        }

        return raw.entries.mapNotNull { (rawEmotion, rawFileName) ->
            val emotionName = rawEmotion?.toString()?.trim().orEmpty()
            val fileName = rawFileName?.toString()?.trim().orEmpty()
            if (emotionName.isEmpty() || fileName.isEmpty()) {
                return@mapNotNull null
            }

            val emotion = runCatching { AvatarEmotion.valueOf(emotionName.uppercase()) }.getOrNull()
                ?: return@mapNotNull null
            emotion to fileName
        }.toMap()
    }

    private fun parseCurrentEmotion(data: Map<String, Any>): AvatarEmotion {
        val raw = data["currentEmotion"]?.toString()?.trim().orEmpty()
        return runCatching { AvatarEmotion.valueOf(raw.uppercase()) }.getOrDefault(AvatarEmotion.IDLE)
    }

    private fun extractAvailableMediaFiles(
        data: Map<String, Any>,
        listKey: String,
        allowedExtensions: Set<String>
    ): List<String> {
        return (data[listKey] as? List<*>)
            ?.mapNotNull { it as? String }
            ?.map { it.trim() }
            ?.filter { fileName ->
                val extension = File(fileName).extension.lowercase()
                fileName.isNotEmpty() && allowedExtensions.contains(extension)
            }
            ?.distinct()
            .orEmpty()
    }

    private fun fallbackEmotionMap(availableFiles: List<String>): Map<AvatarEmotion, String> {
        val firstFile = availableFiles.firstOrNull() ?: return emptyMap()
        return mapOf(AvatarEmotion.IDLE to firstFile)
    }

    private fun inferEmotionToMediaFileMap(fileNames: List<String>): Map<AvatarEmotion, String> {
        if (fileNames.isEmpty()) {
            return emptyMap()
        }

        val normalizedByBaseName = LinkedHashMap<String, String>()
        fileNames.forEach { fileName ->
            val baseName = File(fileName).nameWithoutExtension.lowercase()
            normalizedByBaseName.putIfAbsent(baseName, fileName)
        }

        val candidates = linkedMapOf(
            AvatarEmotion.IDLE to listOf("idle", "default", "normal", "standby"),
            AvatarEmotion.LISTENING to listOf("listening", "talking", "speak", "speaking", "chat"),
            AvatarEmotion.THINKING to listOf("thinking", "think", "loading"),
            AvatarEmotion.HAPPY to listOf("happy", "smile", "joy"),
            AvatarEmotion.SAD to listOf("sad", "cry", "crying", "angry", "mad"),
            AvatarEmotion.CONFUSED to listOf("confused", "shy", "aojiao", "embarrassed"),
            AvatarEmotion.SURPRISED to listOf("surprised", "surprise", "wow")
        )

        return candidates.mapNotNull { (emotion, aliases) ->
            val matched = aliases.firstNotNullOfOrNull { alias -> normalizedByBaseName[alias] }
            matched?.let { emotion to it }
        }.toMap()
    }
}
