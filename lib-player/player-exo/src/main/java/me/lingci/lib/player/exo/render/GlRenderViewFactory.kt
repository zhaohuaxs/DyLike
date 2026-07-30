package me.lingci.lib.player.exo.render

import android.content.Context
import xyz.doikki.videoplayer.render.IRenderView
import xyz.doikki.videoplayer.render.RenderViewFactory

/**
 * GlRenderView 的工厂。参考 TextureRenderViewFactory / MpvSurfaceRenderViewFactory 模式。
 */
class GlRenderViewFactory : RenderViewFactory() {

    override fun createRenderView(context: Context): IRenderView {
        return GlRenderView(context)
    }

    companion object {
        @JvmStatic
        fun create(): GlRenderViewFactory = GlRenderViewFactory()
    }
}
