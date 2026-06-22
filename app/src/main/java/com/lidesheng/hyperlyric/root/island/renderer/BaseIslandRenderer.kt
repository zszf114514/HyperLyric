package com.lidesheng.hyperlyric.root.island.renderer

import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.IslandViewHelper
import com.lidesheng.hyperlyric.root.island.view.MaxWidthFrameLayout
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule

/**
 * 灵动岛渲染器的抽象基类。
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
                    IslandViewHelper.clearInjectedViews(cv)
                    IslandViewHelper.triggerSystemRelayout(cv)
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
                                IslandViewHelper.clearInjectedViews(cv)
                                IslandViewHelper.triggerSystemRelayout(cv)
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

    /**
     * 应用配置选项（如是否隐藏专辑封面或律动图标等）。
     */
    protected fun applySettings(rootView: ViewGroup) {
        val prefs = (module as HookEntry).prefs
        val showAlbum = prefs.getBoolean(RootConstants.KEY_HOOK_ISLAND_LEFT_ALBUM, RootConstants.DEFAULT_HOOK_ISLAND_LEFT_ALBUM)
        val showRhythm = prefs.getBoolean(RootConstants.KEY_HOOK_ISLAND_RIGHT_ICON, RootConstants.DEFAULT_HOOK_ISLAND_RIGHT_ICON)

        IslandViewHelper.toggleContainer(rootView, "island_container_module_image_text_1", "island_container_module_icon", showAlbum)
        IslandViewHelper.toggleContainer(rootView, "island_container_module_image_text_2", "island_container_module_icon", showRhythm)

        IslandViewHelper.toggleContainer(rootView, "island_container_module_image_text_1", "island_container_module_text", true)
        IslandViewHelper.toggleContainer(rootView, "island_container_module_image_text_2", "island_container_module_text", true)

        if (!showAlbum) {
            IslandViewHelper.clearTextContainerMargin(rootView, "island_container_module_image_text_1", clearStart = true, clearEnd = false)
        }
        if (!showRhythm) {
            IslandViewHelper.clearTextContainerMargin(rootView, "island_container_module_image_text_2", clearStart = false, clearEnd = true)
        }
    }

    class SlotConfig(
        val maxWidthDp: Int,
        val paddingLeftDp: Int,
        val paddingRightDp: Int,
        val isLeft: Boolean
    )

    /**
     * 读取指定卡槽（左/右）的最大宽度与内边距配置。
     */
    internal fun readSlotConfig(prefs: SharedPreferences, parentName: String): SlotConfig {
        val isLeft = parentName.contains("1")
        val maxWidthDp = if (isLeft) prefs.getInt(RootConstants.KEY_HOOK_ISLAND_LEFT_CONTENT_MAX_WIDTH, RootConstants.DEFAULT_HOOK_ISLAND_LEFT_CONTENT_MAX_WIDTH)
                         else prefs.getInt(RootConstants.KEY_HOOK_ISLAND_RIGHT_CONTENT_MAX_WIDTH, RootConstants.DEFAULT_HOOK_ISLAND_RIGHT_CONTENT_MAX_WIDTH)
        val pL = if (isLeft) prefs.getInt(RootConstants.KEY_HOOK_ISLAND_LEFT_PADDING_LEFT, RootConstants.DEFAULT_HOOK_ISLAND_LEFT_PADDING_LEFT)
                 else prefs.getInt(RootConstants.KEY_HOOK_ISLAND_RIGHT_PADDING_LEFT, RootConstants.DEFAULT_HOOK_ISLAND_RIGHT_PADDING_LEFT)
        val pR = if (isLeft) prefs.getInt(RootConstants.KEY_HOOK_ISLAND_LEFT_PADDING_RIGHT, RootConstants.DEFAULT_HOOK_ISLAND_LEFT_PADDING_RIGHT)
                 else prefs.getInt(RootConstants.KEY_HOOK_ISLAND_RIGHT_PADDING_RIGHT, RootConstants.DEFAULT_HOOK_ISLAND_RIGHT_PADDING_RIGHT)
        return SlotConfig(maxWidthDp, pL, pR, isLeft)
    }

    /**
     * 确保包装的容器 ([MaxWidthFrameLayout]) 和目标歌词视图被正确挂载在卡槽上。
     */
    internal fun <T : View> ensureSlotWrapper(
        rootView: ViewGroup,
        parentName: String,
        tag: String,
        config: SlotConfig,
        viewCreator: (Context) -> T
    ): Pair<MaxWidthFrameLayout, T>? {
        val parent = IslandViewHelper.findViewByName(rootView, parentName) as? ViewGroup ?: return null
        val container = IslandViewHelper.findViewByName(parent, "island_container_module_text") as? ViewGroup ?: parent

        val wrapperTag = tag + "_WRAPPER"
        var wrapperView = container.findViewWithTag<MaxWidthFrameLayout>(wrapperTag)
        var targetView = container.findViewWithTag<T>(tag)

        if (config.maxWidthDp <= 0) {
            wrapperView?.visibility = View.GONE
            targetView?.visibility = View.GONE
            return null
        }

        val density = rootView.resources.displayMetrics.density

        if (wrapperView == null) {
            targetView?.let { container.removeView(it) }

            wrapperView = MaxWidthFrameLayout(rootView.context).apply {
                this.tag = wrapperTag
                this.clipChildren = true
            }

            targetView = viewCreator(rootView.context).apply {
                this.tag = tag
            }

            wrapperView.addView(targetView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER_VERTICAL
            })

            container.addView(wrapperView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER_VERTICAL
            })
        } else {
            targetView = wrapperView.findViewWithTag(tag) as? T
            if (targetView == null) {
                wrapperView.removeAllViews()
                targetView = viewCreator(rootView.context).apply {
                    this.tag = tag
                }
                wrapperView.addView(targetView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.CENTER_VERTICAL
                })
            }
        }

        wrapperView.maxWidthPx = (config.maxWidthDp * density).toInt()

        targetView.setPadding((config.paddingLeftDp * density).toInt(), 0, (config.paddingRightDp * density).toInt(), 0)
        targetView.visibility = View.VISIBLE
        wrapperView.visibility = View.VISIBLE

        return Pair(wrapperView, targetView)
    }

    /**
     * 隐藏原生容器中除了我们的包装类之外的其他所有文本视图，保证我们的歌词不被覆盖。
     */
    internal fun hideNativeChildren(rootView: ViewGroup, parentName: String, wrapperView: View) {
        val parent = IslandViewHelper.findViewByName(rootView, parentName) as? ViewGroup ?: return
        val container = IslandViewHelper.findViewByName(parent, "island_container_module_text") as? ViewGroup ?: parent
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child != wrapperView) {
                child.visibility = View.GONE
            }
        }
    }

    /**
     * 强制立即完成测量和布局，防止因为异步渲染造成的一帧闪烁/抖动。
     */
    internal fun forceImmediateLayout(rootView: ViewGroup, parentName: String, wrapperView: View, maxWidthDp: Int) {
        val parent = IslandViewHelper.findViewByName(rootView, parentName) as? ViewGroup ?: return
        val container = IslandViewHelper.findViewByName(parent, "island_container_module_text") as? ViewGroup ?: parent
        val density = rootView.resources.displayMetrics.density
        val msW = View.MeasureSpec.makeMeasureSpec((maxWidthDp * density).toInt(), View.MeasureSpec.AT_MOST)
        val msH = View.MeasureSpec.makeMeasureSpec(container.height, if (container.height > 0) View.MeasureSpec.EXACTLY else View.MeasureSpec.UNSPECIFIED)
        wrapperView.measure(msW, msH)
        wrapperView.layout(0, 0, wrapperView.measuredWidth, wrapperView.measuredHeight)
    }
}
