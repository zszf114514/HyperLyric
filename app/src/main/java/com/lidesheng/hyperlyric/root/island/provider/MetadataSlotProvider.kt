package com.lidesheng.hyperlyric.root.island.provider

import android.content.SharedPreferences
import android.view.ViewGroup
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.lyric.model.RichLyricLine
import com.lidesheng.hyperlyric.lyric.view.RichLyricLineView
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.renderer.BaseIslandRenderer
import com.lidesheng.hyperlyric.root.utils.HookLogger
import com.lidesheng.hyperlyric.root.utils.LyricStyleHelper
import com.lidesheng.hyperlyric.root.utils.TranslationHelper

/**
 * 歌曲信息 (Metadata) 注入策略实现。
 */
object MetadataSlotProvider : IslandSlotContentProvider {
    
    override fun inject(
        renderer: BaseIslandRenderer,
        rootView: ViewGroup,
        parentName: String,
        tag: String,
        prefs: SharedPreferences,
        pkgName: String,
        mode: Int
    ) {
        val res = rootView.resources
        val config = renderer.readSlotConfig(prefs, parentName)

        val pair = renderer.ensureSlotWrapper(rootView, parentName, tag, config) { context ->
            RichLyricLineView(context)
        } ?: return

        val wrapperView = pair.first
        val targetView = pair.second

        val targetPkg = LyriconDataBridge.activePackageName ?: pkgName
        val mediaInfo = MediaMetadataHelper.getMediaInfo(rootView.context, targetPkg, HookLogger)

        val songName = LyriconDataBridge.currentSongName?.takeIf { it.isNotEmpty() } ?: mediaInfo.title
        val artistName = mediaInfo.artist
        val albumName = mediaInfo.album
        val albumBitmap = mediaInfo.albumArt

        val singleModeText = when(mode) {
            1 -> songName
            2 -> artistName
            3 -> albumName
            4 -> "$songName - $artistName"
            else -> ""
        }

        val disableAll = TranslationHelper.isTranslationDisabled(prefs)
        val translationOnly = TranslationHelper.isTranslationOnly(prefs)
        targetView.displayTranslation = LyriconDataBridge.isDisplayTranslation && !disableAll
        targetView.displayRoma = LyriconDataBridge.isDisplayRoma && !disableAll && !translationOnly

        val style = LyricStyleHelper.buildStyle(prefs, res, mode, albumBitmap)
        targetView.setStyle(style)

        val newLine = when(mode) {
            1, 2, 3, 4 -> RichLyricLine(text = singleModeText, words = emptyList())
            5 -> RichLyricLine(text = songName, words = emptyList(), secondary = artistName, secondaryWords = emptyList())
            6 -> {
                val sec = if (albumName.isEmpty()) artistName else "$artistName - $albumName"
                RichLyricLine(text = songName, words = emptyList(), secondary = sec, secondaryWords = emptyList())
            }
            else -> null
        }

        val currentLine = targetView.line
        val contentChanged = newLine == null || currentLine == null ||
            currentLine.text != newLine.text || currentLine.secondary != newLine.secondary
        if (contentChanged) {
            targetView.line = newLine
        }
        HookLogger.d("MetadataSlotProvider","歌曲信息装配完成: mode=$mode, 标题=${singleModeText.take(20)}")

        if (prefs.getBoolean(RootConstants.KEY_HOOK_MARQUEE_METADATA_MODE, RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_MODE)) {
            val mdSpeed = prefs.getInt(RootConstants.KEY_HOOK_MARQUEE_METADATA_SPEED, RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_SPEED).toFloat()
            val mdDelay = prefs.getInt(RootConstants.KEY_HOOK_MARQUEE_METADATA_DELAY, RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_DELAY)
            val mdLoopDelay = prefs.getInt(RootConstants.KEY_HOOK_MARQUEE_METADATA_LOOP_DELAY, RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_LOOP_DELAY)
            val mdInfinite = prefs.getBoolean(RootConstants.KEY_HOOK_MARQUEE_METADATA_INFINITE, RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_INFINITE)
            targetView.setMetadataMarqueeConfig(
                speed = mdSpeed,
                initialDelay = mdDelay,
                loopDelay = mdLoopDelay,
                repeatCount = if (mdInfinite) -1 else 1,
                stopAtEnd = true
            )
            targetView.main.setPeerLineWidth(targetView.secondary.lineWidth)
            targetView.secondary.setPeerLineWidth(targetView.main.lineWidth)
        }

        renderer.hideNativeChildren(rootView, parentName, wrapperView)
        renderer.forceImmediateLayout(rootView, parentName, wrapperView, config.maxWidthDp)

        targetView.post {
            if (prefs.getBoolean(RootConstants.KEY_HOOK_MARQUEE_METADATA_MODE, RootConstants.DEFAULT_HOOK_MARQUEE_METADATA_MODE)) {
                targetView.requestStartMarquee()
            }
        }
    }
}
