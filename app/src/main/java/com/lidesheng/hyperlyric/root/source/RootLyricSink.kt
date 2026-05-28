package com.lidesheng.hyperlyric.root.source

import com.lidesheng.hyperlyric.lyric.source.LyricSink
import com.lidesheng.hyperlyric.root.LyriconDataBridge

class RootLyricSink(
    private val renderer: IslandRenderer
) : LyricSink {

    override fun onSongChanged(song: Any?) {
        // LyriconSource 直接操作 LyriconDataBridge，不经过 Sink
    }

    override fun onLyricLine(line: Any?) {
        // LyriconSource 直接操作 LyriconDataBridge，不经过 Sink
    }

    override fun onPlainText(text: String?) {
        // LyriconSource 直接操作 LyriconDataBridge，不经过 Sink
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
