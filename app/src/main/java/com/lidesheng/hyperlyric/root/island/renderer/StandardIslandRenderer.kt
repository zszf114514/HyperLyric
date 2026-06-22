package com.lidesheng.hyperlyric.root.island.renderer

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.lyric.view.RichLyricLineView
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.HookIslandGlow
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.IslandViewHelper
import com.lidesheng.hyperlyric.root.island.provider.LiveLyricSlotProvider
import com.lidesheng.hyperlyric.root.island.provider.MetadataSlotProvider
import com.lidesheng.hyperlyric.root.utils.CoverColorHelper
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain

/**
 * 标准灵动岛渲染器。
 */
object StandardIslandRenderer : BaseIslandRenderer("StandardIslandRenderer") {

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
                val leftMode = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_CONTENT_LEFT, RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_LEFT)
                val rightMode = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_CONTENT_RIGHT, RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_RIGHT)
                
                injectToSlot(islandView, "island_container_module_image_text_1", "HYPERLYRIC_LEFT_VIEW", leftMode, prefs, pkgName)
                injectToSlot(islandView, "island_container_module_image_text_2", "HYPERLYRIC_RIGHT_VIEW", rightMode, prefs, pkgName)
            }
        }.onFailure { e ->
            HookLogger.e("StandardIslandRenderer", "预注入超级岛失败", e)
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
            val leftMode = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_CONTENT_LEFT, RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_LEFT)
            val rightMode = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_CONTENT_RIGHT, RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_RIGHT)
            
            injectToSlot(viewGroup, "island_container_module_image_text_1", "HYPERLYRIC_LEFT_VIEW", leftMode, prefs, pkgName)
            injectToSlot(viewGroup, "island_container_module_image_text_2", "HYPERLYRIC_RIGHT_VIEW", rightMode, prefs, pkgName)

            HookIslandGlow.injectAndTriggerGlow(viewGroup, islandData, prefs)
        }.onFailure { e ->
            HookLogger.e("StandardIslandRenderer", "更新超级岛视图失败", e)
        }

        return result
    }

    @SuppressLint("DiscouragedApi")
    private fun injectToSlot(rootView: ViewGroup, parentName: String, tag: String, mode: Int, prefs: SharedPreferences, pkgName: String) {
        if (mode == 0) {
            val parent = IslandViewHelper.findViewByName(rootView, parentName) as? ViewGroup
            val container = parent?.let { IslandViewHelper.findViewByName(it, "island_container_module_text") as? ViewGroup ?: it }
            container?.findViewWithTag<View>(tag)?.visibility = View.GONE
            return
        }

        val provider = if (mode == 7) LiveLyricSlotProvider else MetadataSlotProvider
        provider.inject(this, rootView, parentName, tag, prefs, pkgName, mode)
    }

    override fun refreshActiveIsland() {
        if ((module as? HookEntry)?.prefs?.getBoolean(RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND) != true) return
        HookLogger.d("StandardIslandRenderer","正在刷新超级岛")
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
                        val leftMode = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_CONTENT_LEFT, RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_LEFT)
                        val rightMode = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_CONTENT_RIGHT, RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_RIGHT)
                        injectToSlot(cv, "island_container_module_image_text_1", "HYPERLYRIC_LEFT_VIEW", leftMode, prefs, pkgName)
                        injectToSlot(cv, "island_container_module_image_text_2", "HYPERLYRIC_RIGHT_VIEW", rightMode, prefs, pkgName)
                        
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
        val iterator = activeIslandPkgNames.entries.iterator()
        val activePkg = LyriconDataBridge.activePackageName
        if (activePkg.isNullOrEmpty()) return

        while (iterator.hasNext()) {
            val entry = iterator.next()
            val cv = entry.key as? ViewGroup
            val pkgName = entry.value

            if (cv != null && cv.isAttachedToWindow) {
                if (pkgName == activePkg) {
                    val prefs = (module as HookEntry).prefs
                    updateLyricInSlot(cv, "HYPERLYRIC_LEFT_VIEW", prefs.getInt(RootConstants.KEY_HOOK_ISLAND_CONTENT_LEFT, RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_LEFT), prefs)
                    updateLyricInSlot(cv, "HYPERLYRIC_RIGHT_VIEW", prefs.getInt(RootConstants.KEY_HOOK_ISLAND_CONTENT_RIGHT, RootConstants.DEFAULT_HOOK_ISLAND_CONTENT_RIGHT), prefs)
                }
            } else {
                iterator.remove()
            }
        }
    }

    private fun updateLyricInSlot(cv: ViewGroup, tag: String, mode: Int, prefs: SharedPreferences) {
        if (mode != 7) return
        LiveLyricSlotProvider.updateLyric(cv, tag, prefs)
    }

    override fun setPositionOnViews(cv: ViewGroup, position: Long) {
        cv.findViewWithTag<RichLyricLineView>("HYPERLYRIC_LEFT_VIEW")?.setPosition(position)
        cv.findViewWithTag<RichLyricLineView>("HYPERLYRIC_RIGHT_VIEW")?.setPosition(position)
    }
}
