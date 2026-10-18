package me.lingci.dy.player.migrate

/**
 * SP→Room 迁移结果统计（§5.2 / §8.2）
 */
data class MigrationResult(
    var sources: Int = 0,
    var media: Int = 0,
    var videos: Int = 0,
    var history: Int = 0,
    var favorites: Int = 0,
    var playlists: Int = 0,
    var playlistItems: Int = 0,
    var shuffles: Int = 0,
    /** 关联媒体不存在而被跳过的随机播放状态数（完成页 >0 时提示） */
    var skippedShuffles: Int = 0,
    /** mediaId 非空但媒体缺失时补建的宿主媒体数（§1.5 规则 2） */
    var rebuiltMedia: Int = 0,
    /** 是否产生了孤儿归并虚拟媒体（§1.5 规则 4） */
    var virtualMedia: Boolean = false,
    /**
     * 因主键/唯一索引已存在而被跳过的行数（合并模式）：
     * 点「稍后再说」后用户仍会写 Room，重迁移时同 id 行以库内为准
     */
    var skippedExisting: Int = 0,
    var elapsedMs: Long = 0
)
