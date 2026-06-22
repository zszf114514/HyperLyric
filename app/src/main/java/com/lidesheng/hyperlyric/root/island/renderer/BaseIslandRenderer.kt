package com.lidesheng.hyperlyric.root.island.renderer

import android.view.View
import android.view.ViewGroup
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.IslandHostFacade
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule

/**
 * 超级岛渲染器的抽象基类。
 */
abstract class BaseIslandRenderer(protected val logTag: String) : IslandRenderer {
    lateinit var module: XposedModule

    // 缓存所有激活了超级岛的 View 及其包名
    val activeIslandPkgNames: MutableMap<View, String> = 
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, String>())
        
    // 缓存当前激活的 BigIsland 容器引用
    var activeContentView: java.lang.ref.WeakReference<ViewGroup>? = null

    override fun clearAllViews() {
        val iterator = activeIslandPkgNames.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val cv = entry.key as? ViewGroup
            if (cv != null && cv.isAttachedToWindow) {
                cv.post {
                    IslandHostFacade.clearAndRefresh(cv)
                }
            } else {
                iterator.remove()
            }
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        val prefs = (module as HookEntry).prefs
        if (!prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND)) {
            activeIslandPkgNames.clear()
            return
        }
        HookLogger.d(logTag, "播放状态变更: isPlaying=$isPlaying")
        val behavior = prefs.getInt(RootConstants.KEY_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE, RootConstants.DEFAULT_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE)

        if (isPlaying) {
            val activePkg = LyriconDataBridge.activePackageName
            val hasInjection = activePkg != null && activeIslandPkgNames.values.any { it == activePkg }
            if (hasInjection) {
                updateLyricLine()
            } else {
                refreshActiveIsland()
            }
        } else {
            when (behavior) {
                0 -> {
                    val iterator = activeIslandPkgNames.entries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        val cv = entry.key as? ViewGroup
                        if (cv != null && cv.isAttachedToWindow) {
                            cv.post {
                                IslandHostFacade.clearAndRefresh(cv)
                            }
                        } else {
                            iterator.remove()
                        }
                    }
                }
                1 -> {
                    // 保持现状，不做处理
                }
            }
        }
    }

    override fun updatePosition(position: Long) {
        if ((module as? HookEntry)?.prefs?.getBoolean(RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND) != true) return
        val iterator = activeIslandPkgNames.entries.iterator()
        val activePkg = LyriconDataBridge.activePackageName ?: return

        while (iterator.hasNext()) {
            val entry = iterator.next()
            val cv = entry.key as? ViewGroup
            val pkgName = entry.value

            if (cv != null && cv.isAttachedToWindow) {
                if (pkgName == activePkg) {
                    cv.post {
                        setPositionOnViews(cv, position)
                    }
                }
            } else {
                iterator.remove()
            }
        }
    }

    /**
     * 子类重写以更新特定类型的 RichLyricLineView 的播放进度。
     */
    protected abstract fun setPositionOnViews(cv: ViewGroup, position: Long)

    // Xposed 事件拦截由具体的子类重写实现
    abstract override fun onPreInject(chain: Chain): Any?
    abstract override fun onUpdateBigIsland(chain: Chain): Any?
}
