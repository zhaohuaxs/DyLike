package me.lingci.dy.player.service

import xyz.doikki.videoplayer.player.AbstractPlayer

/**
 * Activity ↔ PlaybackService 的 Binder 接口。
 *
 * 调用流程:
 * 1. Activity onStop 触发后台:bindService → onServiceConnected → takePlayer(player, metadata)
 * 2. Activity onStart 恢复前台:检查 isHoldingPlayer → returnPlayer() → stopForegroundAndNotification()
 */
class PlaybackBinder(private val service: PlaybackService) : android.os.Binder() {

    /** Activity 把 player 交给 Service 接管。 */
    fun takePlayer(player: AbstractPlayer, metadata: PlaybackMetadata) {
        service.takePlayer(player, metadata)
    }

    /** 设置通知点击应该恢复到哪个 Activity。 */
    fun setSourceActivity(cls: Class<*>) {
        service.setSourceActivity(cls)
    }

    /** Activity 从 Service 取回 player。返回 null 表示 Service 没持有。 */
    fun returnPlayer(): AbstractPlayer? = service.returnPlayer()

    /**
     * 取回 player 并指定是否一并终止 Service。
     * @param stopService true = 最终 reclaim；false = 后台切集等临时取回，保留 FGS。
     */
    fun returnPlayer(stopService: Boolean): AbstractPlayer? = service.returnPlayer(stopService)

    /** 没有 player 时收尾，避免 Started Service / 前台通知残留。 */
    fun stopIfNoPlayer() {
        service.stopIfNoPlayer()
    }

    /** Activity 切了视频,更新通知栏元数据。 */
    fun updateMetadata(metadata: PlaybackMetadata) {
        service.updateMetadata(metadata)
    }

    /** Activity 注册后台切集回调(通知栏上一个/下一个时 Service 调用)。 */
    fun setSkipCallback(callback: ((Int) -> Unit)?) {
        service.skipCallback = callback
    }

    /** Activity 注册"会话已由 Service 终止"回调(通知栏关闭等)。 */
    fun setPlayerClosedCallback(callback: ((String) -> Unit)?) {
        service.playerClosedCallback = callback
    }

    /** Service 是否持有 player(Activity 恢复时判断)。 */
    val isHoldingPlayer: Boolean get() = service.isHoldingPlayer

    /** 关闭前台通知(恢复前台时调用)。 */
    fun stopForegroundAndNotification() {
        service.stopForegroundAndNotification()
    }
}
