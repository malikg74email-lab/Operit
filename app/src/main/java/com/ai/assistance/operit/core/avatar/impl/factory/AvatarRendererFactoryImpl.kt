package com.ai.assistance.operit.core.avatar.impl.factory

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ai.assistance.operit.core.avatar.common.control.AvatarController
import com.ai.assistance.operit.core.avatar.common.factory.AvatarRendererFactory
import com.ai.assistance.operit.core.avatar.common.model.AvatarModel
import com.ai.assistance.operit.core.avatar.impl.mp4.model.Mp4AvatarModel
import com.ai.assistance.operit.core.avatar.impl.mp4.view.Mp4Renderer
import com.ai.assistance.operit.core.avatar.impl.webp.model.WebPAvatarModel
import com.ai.assistance.operit.core.avatar.impl.webp.view.WebPRenderer

class AvatarRendererFactoryImpl : AvatarRendererFactory {

    @Composable
    override fun createRenderer(model: AvatarModel): @Composable ((modifier: Modifier, controller: AvatarController) -> Unit)? {
        return when (model) {
            is WebPAvatarModel -> {
                { modifier, controller ->
                    WebPRenderer(
                        modifier = modifier,
                        model = model,
                        controller = controller,
                        onError = { }
                    )
                }
            }
            is Mp4AvatarModel -> {
                { modifier, controller ->
                    Mp4Renderer(
                        modifier = modifier,
                        model = model,
                        controller = controller,
                        onError = { }
                    )
                }
            }
            else -> null
        }
    }
}
