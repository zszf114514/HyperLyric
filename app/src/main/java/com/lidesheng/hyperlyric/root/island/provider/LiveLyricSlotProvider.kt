package com.lidesheng.hyperlyric.root.island.provider

import android.content.SharedPreferences
import android.view.ViewGroup
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.lyric.model.RichLyricLine
import com.lidesheng.hyperlyric.lyric.view.RichLyricLineView
import com.lidesheng.hyperlyric.lyric.view.yoyo.YoYoPresets
import com.lidesheng.hyperlyric.lyric.view.yoyo.animateUpdate
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.renderer.BaseIslandRenderer
import com.lidesheng.hyperlyric.root.utils.HookLogger
import com.lidesheng.hyperlyric.root.utils.LyricStyleHelper
import com.lidesheng.hyperlyric.root.utils.TranslationHelper

/**
 * 实时歌词 (Live Lyric) 注入与更新策略实现。
 */
object LiveLyricSlotProvider : IslandSlotContentProvider {
    
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
        
        // 确保 MaxWidthFrameLayout 容器 and RichLyricLineView 被正确挂载
        val pair = renderer.ensureSlotWrapper(rootView, parentName, tag, config) { context ->
            RichLyricLineView(context)
        } ?: return

        val wrapperView = pair.first
        val targetView = pair.second

        val targetPkg = LyriconDataBridge.activePackageName ?: pkgName
        val mediaInfo = MediaMetadataHelper.getMediaInfo(rootView.context, targetPkg, HookLogger)
        val songName = LyriconDataBridge.currentSongName?.takeIf { it.isNotEmpty() } ?: mediaInfo.title

        configureRichLyricView(targetView, prefs, res, mode, null)

        var rawLine = LyriconDataBridge.currentLyricLine ?: RichLyricLine(text = songName, words = emptyList())
        if (TranslationHelper.isTranslationOnly(prefs)) {
            rawLine = TranslationHelper.applyTranslationOnly(rawLine)
        } else if (TranslationHelper.isSwapTranslation(prefs)) {
            rawLine = TranslationHelper.swapTranslation(rawLine)
        }
        
        targetView.line = rawLine
        HookLogger.d("LiveLyricSlotProvider","实时歌词注入完成: 歌词=${(LyriconDataBridge.currentLyric ?: "").take(20)}")

        renderer.hideNativeChildren(rootView, parentName, wrapperView)
        renderer.forceImmediateLayout(rootView, parentName, wrapperView, config.maxWidthDp)

        targetView.post {
            if (prefs.getBoolean(RootConstants.KEY_HOOK_MARQUEE_MODE, RootConstants.DEFAULT_HOOK_MARQUEE_MODE)) {
                targetView.requestStartMarquee()
            }
        }
    }

    private fun configureRichLyricView(view: RichLyricLineView, prefs: SharedPreferences, res: android.content.res.Resources, mode: Int, albumBitmap: android.graphics.Bitmap? = null) {
        val disableAll = TranslationHelper.isTranslationDisabled(prefs)
        val translationOnly = TranslationHelper.isTranslationOnly(prefs)
        view.displayTranslation = LyriconDataBridge.isDisplayTranslation && !disableAll
        view.displayRoma = LyriconDataBridge.isDisplayRoma && !disableAll && !translationOnly

        val style = LyricStyleHelper.buildStyle(prefs, res, mode, albumBitmap)
        view.setStyle(style)
    }

    /**
     * 独立解耦的实时歌词高亮动画更新入口
     */
    fun updateLyric(cv: ViewGroup, tag: String, prefs: SharedPreferences) {
        val view = cv.findViewWithTag<RichLyricLineView>(tag) ?: return
        val rawLine = LyriconDataBridge.currentLyricLine
        val targetLine = if (rawLine != null) {
            if (TranslationHelper.isTranslationOnly(prefs)) {
                TranslationHelper.applyTranslationOnly(rawLine)
            } else if (TranslationHelper.isSwapTranslation(prefs)) {
                TranslationHelper.swapTranslation(rawLine)
            } else {
                rawLine
            }
        } else {
            null
        }

        cv.post {
            val isAnimEnabled = prefs.getBoolean(RootConstants.KEY_HOOK_ANIM_ENABLE, RootConstants.DEFAULT_HOOK_ANIM_ENABLE)
            val animId = prefs.getString(RootConstants.KEY_HOOK_ANIM_ID, RootConstants.DEFAULT_HOOK_ANIM_ID)

            val applyLine: RichLyricLineView.() -> Unit = {
                val disableAll = TranslationHelper.isTranslationDisabled(prefs)
                val translationOnly = TranslationHelper.isTranslationOnly(prefs)
                displayTranslation = LyriconDataBridge.isDisplayTranslation && !disableAll
                displayRoma = LyriconDataBridge.isDisplayRoma && !disableAll && !translationOnly
                line = targetLine
                post {
                    if (prefs.getBoolean(RootConstants.KEY_HOOK_MARQUEE_MODE, RootConstants.DEFAULT_HOOK_MARQUEE_MODE)) {
                        requestStartMarquee()
                    }
                }
            }

            if (isAnimEnabled) {
                val preset = YoYoPresets.getById(animId) ?: YoYoPresets.Default
                view.animateUpdate(preset, applyLine)
            } else {
                view.applyLine()
            }
        }
    }
}
