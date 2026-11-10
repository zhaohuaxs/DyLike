package me.lingci.dy.player.ui.short_video

import android.content.Context
import android.content.res.Configuration
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.dp
import me.lingci.lib.player.view.SubtitleView
import me.lingci.lib.player.widget.component.SubtitleControlView
import me.lingci.lib.player.widget.videoview.CustomVideoView
import xyz.doikki.videoplayer.player.VideoView

/**
 * 短视频字幕停靠控制器。
 *
 * 普通竖屏界面播放横屏视频时，字幕需要避开画面并停靠到底部控制栏区域；全屏、横屏 UI、
 * 竖屏视频或布局未就绪时则恢复普通字幕布局。
 */
class SubtitleDockController(
    private val context: Context,
    private val videoViewProvider: () -> CustomVideoView,
    private val subtitleControlView: SubtitleControlView,
    private val currentPosition: () -> Int
) {

    /** 根据当前渲染视图位置重新计算字幕停靠区域。 */
    fun update() {
        val videoView = videoViewProvider()
        videoView.post {
            val subtitleWidth = subtitleControlView.width
            val subtitleHeight = subtitleControlView.height
            if (subtitleWidth <= 0 || subtitleHeight <= 0) {
                traceDecision(
                    reason = "host-not-ready",
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight
                )
                reset()
                return@post
            }
            if (videoViewProvider().isFullScreen || context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                traceDecision(
                    reason = if (videoViewProvider().isFullScreen) "fullscreen" else "landscape-ui",
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight
                )
                reset()
                return@post
            }
            val videoSize = videoViewProvider().videoSize
            val videoWidth = videoSize.getOrNull(0) ?: 0
            val videoHeight = videoSize.getOrNull(1) ?: 0
            if (videoWidth <= 0 || videoHeight <= 0 || videoWidth < videoHeight) {
                traceDecision(
                    reason = if (videoWidth <= 0 || videoHeight <= 0) "video-size-invalid" else "portrait-video",
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight
                )
                reset()
                return@post
            }
            val playerContainer = videoViewProvider().getChildAt(0) as? ViewGroup ?: run {
                traceDecision(
                    reason = "player-container-missing",
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight
                )
                reset()
                return@post
            }
            val renderView = videoViewProvider().getRenderTransformView() ?: playerContainer.getChildAt(0) ?: run {
                traceDecision(
                    reason = "render-view-missing",
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight,
                    containerWidth = playerContainer.width,
                    containerHeight = playerContainer.height
                )
                reset()
                return@post
            }
            if (renderView.width <= 0 || renderView.height <= 0) {
                traceDecision(
                    reason = "render-not-ready",
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight,
                    containerWidth = playerContainer.width,
                    containerHeight = playerContainer.height,
                    renderWidth = renderView.width,
                    renderHeight = renderView.height
                )
                reset()
                return@post
            }

            val containerWidth = playerContainer.width
            val containerHeight = playerContainer.height
            if (containerHeight <= 0) {
                traceDecision(
                    reason = "container-not-ready",
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight,
                    containerWidth = containerWidth,
                    containerHeight = containerHeight,
                    renderWidth = renderView.width,
                    renderHeight = renderView.height
                )
                reset()
                return@post
            }

            val renderBounds = calculateRenderBoundsInParent(renderView, playerContainer)
            if (renderBounds.isEmpty) {
                traceDecision(
                    reason = "render-bounds-empty",
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight,
                    containerWidth = containerWidth,
                    containerHeight = containerHeight,
                    renderWidth = renderView.width,
                    renderHeight = renderView.height,
                    renderBounds = renderBounds
                )
                reset()
                return@post
            }
            val dockingTop = renderBounds.bottom.coerceIn(0f, containerHeight.toFloat()).toInt()
            val bottomBarHeight = (containerHeight - dockingTop).coerceAtLeast(0)
            val minDockHeight = if (videoWidth == videoHeight) 28f.dp.toInt() else 56f.dp.toInt()
            if (bottomBarHeight < minDockHeight) {
                traceDecision(
                    reason = "bottom-bar-too-small",
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight,
                    containerWidth = containerWidth,
                    containerHeight = containerHeight,
                    renderWidth = renderView.width,
                    renderHeight = renderView.height,
                    renderBounds = renderBounds,
                    dockingTop = dockingTop,
                    bottomBarHeight = bottomBarHeight,
                    minDockHeight = minDockHeight
                )
                reset()
                return@post
            }

            val boundsTop = dockingTop.coerceIn(0, subtitleHeight)
            val boundsBottom = subtitleHeight
            if (boundsBottom <= boundsTop) {
                traceDecision(
                    reason = "layout-bounds-invalid",
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    subtitleWidth = subtitleWidth,
                    subtitleHeight = subtitleHeight,
                    containerWidth = containerWidth,
                    containerHeight = containerHeight,
                    renderWidth = renderView.width,
                    renderHeight = renderView.height,
                    renderBounds = renderBounds,
                    dockingTop = dockingTop,
                    bottomBarHeight = bottomBarHeight,
                    minDockHeight = minDockHeight,
                    layoutBounds = intArrayOf(0, boundsTop, subtitleWidth, boundsBottom)
                )
                reset()
                return@post
            }
            subtitleControlView.setSubtitleDockMode(SubtitleView.DOCK_MODE_BOTTOM_BAR)
            subtitleControlView.setSubtitleLayoutBounds(0, boundsTop, subtitleWidth, boundsBottom)
            traceDecision(
                reason = "apply-bottom-bar",
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                subtitleWidth = subtitleWidth,
                subtitleHeight = subtitleHeight,
                containerWidth = containerWidth,
                containerHeight = containerHeight,
                renderWidth = renderView.width,
                renderHeight = renderView.height,
                renderBounds = renderBounds,
                dockingTop = dockingTop,
                bottomBarHeight = bottomBarHeight,
                minDockHeight = minDockHeight,
                layoutBounds = intArrayOf(0, boundsTop, subtitleWidth, boundsBottom),
                dockMode = SubtitleView.DOCK_MODE_BOTTOM_BAR
            )
        }
    }

    /** 根据普通/全屏状态应用对应的字幕字号缩放。 */
    fun applyTextSizeForPlayerState(playerState: Int, normalScale: Float, fullScreenScale: Float) {
        val scale = if (playerState == VideoView.PLAYER_FULL_SCREEN) fullScreenScale else normalScale
        subtitleControlView.setSubtitleScale(scale)
    }

    /** 恢复默认字幕布局，清除底部停靠区域。 */
    fun reset() {
        subtitleControlView.setSubtitleDockMode(SubtitleView.DOCK_MODE_NORMAL)
        subtitleControlView.clearSubtitleLayoutBounds()
    }

    /** 计算渲染视图在播放器容器内的真实可见边界，包含缩放/平移矩阵影响。 */
    private fun calculateRenderBoundsInParent(renderView: View, parent: View): RectF {
        val rect = RectF(0f, 0f, renderView.width.toFloat(), renderView.height.toFloat())
        val matrix = android.graphics.Matrix(renderView.matrix)
        matrix.mapRect(rect)
        rect.offset(renderView.left.toFloat(), renderView.top.toFloat())
        rect.intersect(0f, 0f, parent.width.toFloat(), parent.height.toFloat())
        return rect
    }

    /** 输出每次停靠判断的关键参数，便于排查不同比例视频的字幕布局问题。 */
    private fun traceDecision(
        reason: String,
        videoWidth: Int = 0,
        videoHeight: Int = 0,
        subtitleWidth: Int = 0,
        subtitleHeight: Int = 0,
        containerWidth: Int = 0,
        containerHeight: Int = 0,
        renderWidth: Int = 0,
        renderHeight: Int = 0,
        renderBounds: RectF? = null,
        dockingTop: Int = -1,
        bottomBarHeight: Int = -1,
        minDockHeight: Int = -1,
        layoutBounds: IntArray? = null,
        dockMode: Int = subtitleControlView.getDockMode()
    ) {
        Log.d(
            SUBTITLE_DOCK_TRACE_TAG,
            "event=subtitle_dock_update",
            "reason=$reason",
            "curPos=${currentPosition()}",
            "playerState=${if (videoViewProvider().isFullScreen) "fullscreen" else "normal"}",
            "orientation=${if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"}",
            "video=${videoWidth}x${videoHeight}",
            "subtitleHost=${subtitleWidth}x${subtitleHeight}",
            "container=${containerWidth}x${containerHeight}",
            "render=${renderWidth}x${renderHeight}",
            "renderBounds=${formatRectF(renderBounds)}",
            "dockTop=$dockingTop",
            "bottomBar=$bottomBarHeight",
            "minDock=$minDockHeight",
            "layout=${formatBounds(layoutBounds)}",
            "dockMode=${formatDockMode(dockMode)}"
        )
    }

    private fun formatRectF(rect: RectF?): String {
        if (rect == null) return "null"
        return "[${rect.left.toInt()},${rect.top.toInt()},${rect.right.toInt()},${rect.bottom.toInt()}]"
    }

    private fun formatBounds(bounds: IntArray?): String {
        if (bounds == null || bounds.size < 4) return "null"
        return "[${bounds[0]},${bounds[1]},${bounds[2]},${bounds[3]}]"
    }

    private fun formatDockMode(mode: Int): String {
        return if (mode == SubtitleView.DOCK_MODE_BOTTOM_BAR) "bottom_bar" else "normal"
    }

    private companion object {
        const val SUBTITLE_DOCK_TRACE_TAG = "SubtitleDockTrace"
    }
}
