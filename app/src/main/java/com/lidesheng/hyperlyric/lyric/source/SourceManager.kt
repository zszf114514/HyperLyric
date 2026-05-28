package com.lidesheng.hyperlyric.lyric.source

import android.content.Context
import android.util.Log

class SourceManager(
    private val context: Context,
    private val processType: ProcessType,
    private val sink: LyricSink,
    private val sources: List<LyricSource>
) {
    enum class ProcessType { ROOT, APP }

    private var activeSource: LyricSource? = null

    fun start() {
        val sourceId = getDefaultSourceId()
        val source = sources.find { it.id == sourceId && it.isAvailable() }
            ?: sources.firstOrNull { it.isAvailable() }

        if (source == null) {
            Log.w("SourceManager", "没有可用的歌词源 (process=$processType)")
            return
        }

        activeSource = source
        Log.i("SourceManager", "启动歌词源: ${source.displayName} (process=$processType)")
        source.start(sink)
    }

    fun switchSource(sourceId: String) {
        val current = activeSource
        if (current?.id == sourceId) return

        current?.stop()

        val source = sources.find { it.id == sourceId && it.isAvailable() }
        if (source == null) {
            Log.w("SourceManager", "歌词源不可用: $sourceId")
            return
        }

        activeSource = source
        Log.i("SourceManager", "切换歌词源: ${source.displayName}")
        source.start(sink)
    }

    fun getActiveSource(): LyricSource? = activeSource

    fun getAvailableSources(): List<LyricSource> = sources.filter { it.isAvailable() }

    fun stop() {
        activeSource?.stop()
        activeSource = null
    }

    private fun getDefaultSourceId(): String = when (processType) {
        ProcessType.ROOT -> "lyricon"
        ProcessType.APP -> "metadata"
    }
}
