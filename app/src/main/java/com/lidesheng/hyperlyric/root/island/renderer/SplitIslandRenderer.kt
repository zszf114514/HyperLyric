package com.lidesheng.hyperlyric.root.island.renderer

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.lyric.RichLyricLineSplitter
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.lyric.model.RichLyricLine
import com.lidesheng.hyperlyric.lyric.model.interfaces.IRichLyricLine
import com.lidesheng.hyperlyric.lyric.view.SpaceGateRichLyricLineView
import com.lidesheng.hyperlyric.lyric.view.yoyo.YoYoPresets
import com.lidesheng.hyperlyric.lyric.view.yoyo.animateUpdate
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.HookIslandGlow
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.IslandViewHelper
import com.lidesheng.hyperlyric.root.utils.CoverColorHelper
import com.lidesheng.hyperlyric.root.utils.HookLogger
import com.lidesheng.hyperlyric.root.utils.LyricStyleHelper
import com.lidesheng.hyperlyric.root.utils.TranslationHelper
import io.github.libxposed.api.XposedInterface.Chain

/**
 * 左右分离歌词模式灵动岛渲染器。
 */
object SplitIslandRenderer : BaseIslandRenderer("SplitIslandRenderer") {

    private var loggedCutoutInfo = false

    override fun onPreInject(chain: Chain): Any? {
        runCatching {
            val islandView = chain.thisObject as? ViewGroup ?: return@runCatching
            val prefs = (module as HookEntry).prefs
            if (!prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND)) return chain.proceed()
            val pkgName = activeIslandPkgNames[islandView]
            val activePkg = LyriconDataBridge.activePackageName
            val behavior = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE, RootConstants.DEFAULT_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE)

            if (activePkg.isNullOrEmpty() || (!MediaMetadataHelper.isPackagePlaying(islandView.context, activePkg) && behavior == 0)) {
                return@runCatching
            }

            if (pkgName != null && pkgName == activePkg) {
                applySettings(islandView)
                injectToSlot(islandView, "island_container_module_image_text_1", "HYPERLYRIC_LEFT_VIEW", prefs, pkgName)
                injectToSlot(islandView, "island_container_module_image_text_2", "HYPERLYRIC_RIGHT_VIEW", prefs, pkgName)
                linkViews(islandView)
            }
        }.onFailure { e ->
            HookLogger.e("SplitIslandRenderer", "预注入超级岛失败", e)
        }
        return chain.proceed()
    }

    override fun onUpdateBigIsland(chain: Chain): Any? {
        val result = chain.proceed()
        runCatching {
            val viewGroup = chain.thisObject as? ViewGroup ?: return@runCatching
            val prefs = (module as HookEntry).prefs
            if (!prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND)) return result

            val islandData = chain.args.getOrNull(0)
            var pkgName = runCatching {
                val getExtrasMethod = islandData?.javaClass?.methods?.find {
                    it.name == "getExtras" && it.parameterTypes.isEmpty() && it.returnType.name.contains("Bundle")
                }
                val extras = getExtrasMethod?.invoke(islandData) as? android.os.Bundle
                extras?.getString("miui.pkg.name")
            }.getOrNull() ?: ""

            if (pkgName.isNotEmpty()) {
                activeIslandPkgNames[viewGroup] = pkgName
            } else {
                pkgName = activeIslandPkgNames[viewGroup] ?: ""
            }

            val activePkg = LyriconDataBridge.activePackageName
            val behavior = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE, RootConstants.DEFAULT_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE)

            if (activePkg.isNullOrEmpty() || (!MediaMetadataHelper.isPackagePlaying(viewGroup.context, activePkg) && behavior == 0)) {
                IslandViewHelper.clearInjectedViews(viewGroup)
                return@runCatching
            }

            if (pkgName.isEmpty() || pkgName != activePkg) {
                return@runCatching
            }

            activeContentView = java.lang.ref.WeakReference(viewGroup)

            applySettings(viewGroup)

            // 分割歌词为左右两部分
            val songName = LyriconDataBridge.currentSongName?.takeIf { it.isNotEmpty() } ?: ""
            var rawLine = LyriconDataBridge.currentLyricLine ?: RichLyricLine(text = songName, words = emptyList())
            if (TranslationHelper.isTranslationOnly(prefs)) {
                rawLine = TranslationHelper.applyTranslationOnly(rawLine)
            } else if (TranslationHelper.isSwapTranslation(prefs)) {
                rawLine = TranslationHelper.swapTranslation(rawLine)
            }

            val splitResult = if (rawLine.text.isNullOrEmpty()) {
                null
            } else {
                val density = viewGroup.resources.displayMetrics.density
                val leftMaxDp = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_LEFT_CONTENT_MAX_WIDTH, RootConstants.DEFAULT_HOOK_ISLAND_LEFT_CONTENT_MAX_WIDTH)
                val leftMaxPx = leftMaxDp * density
                val textPaint = android.text.TextPaint().apply {
                    textSize = prefs.getInt(RootConstants.KEY_HOOK_TEXT_SIZE, RootConstants.DEFAULT_HOOK_TEXT_SIZE).toFloat() * density
                }
                val centerLyric = prefs.getBoolean(RootConstants.KEY_HOOK_CENTER_LYRIC, RootConstants.DEFAULT_HOOK_CENTER_LYRIC)
                val splitPx = if (centerLyric) {
                    val textWidth = textPaint.measureText(rawLine.text)
                    (textWidth / 2f).coerceAtMost(leftMaxPx)
                } else {
                    leftMaxPx
                }
                val textSizeRatio = prefs.getFloat(RootConstants.KEY_HOOK_TEXT_SIZE_RATIO, RootConstants.DEFAULT_HOOK_TEXT_SIZE_RATIO)
                RichLyricLineSplitter.split(rawLine, textPaint, splitPx, textSizeRatio, centerLyric)
            }

            injectToSlot(viewGroup, "island_container_module_image_text_1", "HYPERLYRIC_LEFT_VIEW", prefs, pkgName, splitResult?.left)
            injectToSlot(viewGroup, "island_container_module_image_text_2", "HYPERLYRIC_RIGHT_VIEW", prefs, pkgName, splitResult?.right)
            linkViews(viewGroup)

            HookIslandGlow.injectAndTriggerGlow(viewGroup, islandData, prefs)
        }.onFailure { e ->
            HookLogger.e("SplitIslandRenderer", "更新超级岛视图失败", e)
        }

        return result
    }

    private fun linkViews(rootView: ViewGroup) {
        val leftView = rootView.findViewWithTag<SpaceGateRichLyricLineView>("HYPERLYRIC_LEFT_VIEW")
        val rightView = rootView.findViewWithTag<SpaceGateRichLyricLineView>("HYPERLYRIC_RIGHT_VIEW")

        leftView?.main?.spaceGateEnabled = false
        leftView?.secondary?.spaceGateEnabled = false
        rightView?.main?.spaceGateEnabled = false
        rightView?.secondary?.spaceGateEnabled = false

        if (!loggedCutoutInfo && leftView != null && rightView != null) {
            val cutoutView = IslandViewHelper.findViewByName(rootView, "area_cutout")
            if (cutoutView != null) {
                val cutoutLoc = IntArray(2)
                cutoutView.getLocationOnScreen(cutoutLoc)
                HookLogger.d("SplitIslandRenderer","成功定位摄像头容器(area_cutout), 宽度 = ${cutoutView.width}px, 绝对X = ${cutoutLoc[0]}")
            } else {
                HookLogger.d("SplitIslandRenderer","未找到系统 area_cutout 容器，将使用几何居中fallback。")
            }
            loggedCutoutInfo = true
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun injectToSlot(rootView: ViewGroup, parentName: String, tag: String, prefs: SharedPreferences, pkgName: String, lineOverride: IRichLyricLine? = null) {
        val res = rootView.resources
        val config = readSlotConfig(prefs, parentName)

        val pair = ensureSlotWrapper(rootView, parentName, tag, config) { context ->
            SpaceGateRichLyricLineView(context)
        } ?: return

        val wrapperView = pair.first
        val targetView = pair.second

        configureRichLyricView(targetView, prefs, res)
        
        val rawLine = lineOverride ?: run {
            val songName = LyriconDataBridge.currentSongName?.takeIf { it.isNotEmpty() } ?: ""
            var line = LyriconDataBridge.currentLyricLine ?: RichLyricLine(text = songName, words = emptyList())
            if (TranslationHelper.isTranslationOnly(prefs)) {
                line = TranslationHelper.applyTranslationOnly(line)
            } else if (TranslationHelper.isSwapTranslation(prefs)) {
                line = TranslationHelper.swapTranslation(line)
            }
            line
        }
        targetView.line = rawLine

        hideNativeChildren(rootView, parentName, wrapperView)
        forceImmediateLayout(rootView, parentName, wrapperView, config.maxWidthDp)

        targetView.post {
            if (prefs.getBoolean(RootConstants.KEY_HOOK_MARQUEE_MODE, RootConstants.DEFAULT_HOOK_MARQUEE_MODE)) {
                targetView.requestStartMarquee()
            }
        }
    }

    private fun configureRichLyricView(view: SpaceGateRichLyricLineView, prefs: SharedPreferences, res: android.content.res.Resources) {
        val disableAll = TranslationHelper.isTranslationDisabled(prefs)
        val translationOnly = TranslationHelper.isTranslationOnly(prefs)
        view.displayTranslation = LyriconDataBridge.isDisplayTranslation && !disableAll
        view.displayRoma = LyriconDataBridge.isDisplayRoma && !disableAll && !translationOnly

        val style = LyricStyleHelper.buildStyle(prefs, res, 7, null)
        view.setStyle(style)
    }

    override fun refreshActiveIsland() {
        if ((module as? HookEntry)?.prefs?.getBoolean(RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND) != true) return
        HookLogger.d("SplitIslandRenderer","正在刷新超级岛")
        val iterator = activeIslandPkgNames.entries.iterator()
        val activePkg = LyriconDataBridge.activePackageName ?: return

        while (iterator.hasNext()) {
            val entry = iterator.next()
            val cv = entry.key as? ViewGroup
            val pkgName = entry.value

            if (cv != null && cv.isAttachedToWindow) {
                if (pkgName == activePkg) {
                    cv.post {
                        val prefs = (module as HookEntry).prefs
                        CoverColorHelper.clearCache()
                        applySettings(cv)
                        
                        injectToSlot(cv, "island_container_module_image_text_1", "HYPERLYRIC_LEFT_VIEW", prefs, pkgName)
                        injectToSlot(cv, "island_container_module_image_text_2", "HYPERLYRIC_RIGHT_VIEW", prefs, pkgName)
                        linkViews(cv)
                        
                        val mediaInfo = MediaMetadataHelper.getMediaInfo(cv.context, pkgName, HookLogger)
                        HookIslandGlow.updateMusicGlow(mediaInfo.albumArt, prefs)

                        IslandViewHelper.triggerSystemRelayout(cv)
                    }
                }
            } else {
                iterator.remove()
            }
        }
    }

    override fun updateLyricLine() {
        if ((module as? HookEntry)?.prefs?.getBoolean(RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND) != true) return
        HookLogger.d("SplitIslandRenderer","正在更新歌词行")
        val iterator = activeIslandPkgNames.entries.iterator()
        val activePkg = LyriconDataBridge.activePackageName ?: return

        while (iterator.hasNext()) {
            val entry = iterator.next()
            val cv = entry.key as? ViewGroup
            val pkgName = entry.value

            if (cv != null && cv.isAttachedToWindow) {
                if (pkgName == activePkg) {
                    val prefs = (module as HookEntry).prefs

                    var rawLine = LyriconDataBridge.currentLyricLine
                    if (rawLine != null) {
                        if (TranslationHelper.isTranslationOnly(prefs)) {
                            rawLine = TranslationHelper.applyTranslationOnly(rawLine)
                        } else if (TranslationHelper.isSwapTranslation(prefs)) {
                            rawLine = TranslationHelper.swapTranslation(rawLine)
                        }
                    }
                    val splitResult = if (rawLine != null && !rawLine.text.isNullOrEmpty()) {
                        val density = cv.resources.displayMetrics.density
                        val leftMaxDp = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_LEFT_CONTENT_MAX_WIDTH, RootConstants.DEFAULT_HOOK_ISLAND_LEFT_CONTENT_MAX_WIDTH)
                        val leftMaxPx = leftMaxDp * density
                        val textPaint = android.text.TextPaint().apply {
                            textSize = prefs.getInt(RootConstants.KEY_HOOK_TEXT_SIZE, RootConstants.DEFAULT_HOOK_TEXT_SIZE).toFloat() * density
                        }
                        val centerLyric = prefs.getBoolean(RootConstants.KEY_HOOK_CENTER_LYRIC, RootConstants.DEFAULT_HOOK_CENTER_LYRIC)
                        val splitPx = if (centerLyric) {
                            val textWidth = textPaint.measureText(rawLine.text!!)
                            (textWidth / 2f).coerceAtMost(leftMaxPx)
                        } else {
                            leftMaxPx
                        }
                        val textSizeRatio = prefs.getFloat(RootConstants.KEY_HOOK_TEXT_SIZE_RATIO, RootConstants.DEFAULT_HOOK_TEXT_SIZE_RATIO)
                        RichLyricLineSplitter.split(rawLine, textPaint, splitPx, textSizeRatio, centerLyric)
                    } else {
                        null
                    }

                    updateLyricInSlot(cv, "HYPERLYRIC_LEFT_VIEW", prefs, splitResult?.left)
                    updateLyricInSlot(cv, "HYPERLYRIC_RIGHT_VIEW", prefs, splitResult?.right)
                }
            } else {
                iterator.remove()
            }
        }
    }

    private fun updateLyricInSlot(cv: ViewGroup, tag: String, prefs: SharedPreferences, lineOverride: IRichLyricLine? = null) {
        val view = cv.findViewWithTag<SpaceGateRichLyricLineView>(tag) ?: return
        val targetLine = lineOverride ?: run {
            val rawLine = LyriconDataBridge.currentLyricLine
            if (rawLine != null) {
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
        }

        cv.post {
            val isAnimEnabled = prefs.getBoolean(RootConstants.KEY_HOOK_ANIM_ENABLE, RootConstants.DEFAULT_HOOK_ANIM_ENABLE)
            val animId = prefs.getString(RootConstants.KEY_HOOK_ANIM_ID, RootConstants.DEFAULT_HOOK_ANIM_ID)

            val applyLine: SpaceGateRichLyricLineView.() -> Unit = {
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

    override fun setPositionOnViews(cv: ViewGroup, position: Long) {
        cv.findViewWithTag<SpaceGateRichLyricLineView>("HYPERLYRIC_LEFT_VIEW")?.setPosition(position)
        cv.findViewWithTag<SpaceGateRichLyricLineView>("HYPERLYRIC_RIGHT_VIEW")?.setPosition(position)
    }
}
