package com.ai.assistance.operit.core.avatar.impl.factory

import androidx.compose.runtime.Composable
import com.ai.assistance.operit.core.avatar.common.control.AvatarController
import com.ai.assistance.operit.core.avatar.common.factory.AvatarControllerFactory
import com.ai.assistance.operit.core.avatar.common.model.AvatarModel
import com.ai.assistance.operit.core.avatar.common.model.AvatarType
import com.ai.assistance.operit.core.avatar.impl.mp4.control.rememberMp4AvatarController
import com.ai.assistance.operit.core.avatar.impl.mp4.model.Mp4AvatarModel
import com.ai.assistance.operit.core.avatar.impl.webp.control.rememberWebPAvatarController
import com.ai.assistance.operit.core.avatar.impl.webp.model.WebPAvatarModel

class AvatarControllerFactoryImpl : AvatarControllerFactory {

    @Composable
    override fun createController(model: AvatarModel): AvatarController? {
        return when (model) {
            is WebPAvatarModel -> rememberWebPAvatarController(model)
            is Mp4AvatarModel -> rememberMp4AvatarController(model)
            else -> null
        }
    }

    override fun canCreateController(model: AvatarModel): Boolean {
        return model is WebPAvatarModel || model is Mp4AvatarModel
    }

    override val supportedTypes: List<String>
        get() = listOf(
            AvatarType.WEBP.name,
            AvatarType.MP4.name
        )
}
