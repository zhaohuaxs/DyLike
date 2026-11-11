package me.lingci.lib.player.listener

import me.lingci.lib.player.chapter.ChapterNode


interface OnVisibilityChangedListener {
    fun onVisibilityChanged(isVisible: Boolean)
}

interface OnPlayNextListener {
    fun onPreviousPlay()
    fun onNextPlay()
}

interface OnFontChangeListener {
    fun onFontChange(fontPath: String)
}

interface OnLongVideoListener {

    fun onDmShow(isShow: Boolean)
    fun onSelectDm()
    fun onConfDm()
    fun onConfVideo()
    fun onScreenshot()
    fun onShowDmTrack()
    fun onShowDmList()
    fun onShowTrackPanel()
    fun onSpeedChange()
    /** 手动进入画中画 */
    fun onEnterPiP()
    fun onShowEpisodeSelect()
    fun onTimeSync()
    fun onVideoProgress(duration: Int, position: Int)
    fun onShowMediaInfo()
    /** 显示清晰度选择（仅多档 m3u8 时可用） */
    fun onShowQualitySelect() {}
    //fun onShowChapters()

}

interface OnChapterListener {
    /** 章节列表加载完成 */
    fun onChaptersLoaded(chapters: List<ChapterNode>)

    /** 当前章节变化 */
    fun onCurrentChapterChanged(chapter: ChapterNode?)
}
