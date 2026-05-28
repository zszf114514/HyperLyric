package com.lidesheng.hyperlyric.root.source

import com.lidesheng.hyperlyric.lyric.source.LyricSink
import com.lidesheng.hyperlyric.root.LyriconDataBridge

class RootLyricSink(
    private val renderer: IslandRenderer
) : LyricSink {

    override fun onSongChanged(song: Any?) {
        // Song 更新由 LyriconSource 直接操作 LyriconDataBridge
    }

    override fun onLyricLine(line: Any?) {
        // 逐字歌词由 LyriconSource 直接操作 LyriconDataBridge
    }

    override fun onPlainText(text: String?) {
        // 纯文本由 LyriconSource 直接操作 LyriconDataBridge
    }

    override fun onStop() {
        LyriconDataBridge.clearAll()
        renderer.refreshActiveIsland()
    }

    override fun onMetadata(title: String?, artist: String?, album: String?) {
        // Root 进程不使用 Metadata 回调
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        LyriconDataBridge.isPlaying = isPlaying
        renderer.onPlaybackStateChanged(isPlaying)
    }

    override fun onPositionChanged(position: Long) {
        val lyricChanged = LyriconDataBridge.updatePosition(position)
        if (lyricChanged) {
            renderer.updateLyricLine()
        }
        renderer.updatePosition(position)
    }
}
