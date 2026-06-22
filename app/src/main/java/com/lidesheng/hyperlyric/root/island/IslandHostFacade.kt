package com.lidesheng.hyperlyric.root.island

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.root.HookIslandGlow
import com.lidesheng.hyperlyric.root.island.view.MaxWidthFrameLayout
import com.lidesheng.hyperlyric.root.utils.HookLogger

/**
 * 超级岛宿主门面类。
 * 将所有对于 HyperOS (MIUI SystemUI) 插件宿主的 View 树查找、反射字段/方法提取、
 * 背景音乐光效触发、摄像头挖孔定位以及强行即时测排等“脏活”隔离在此门面之下。
 */
internal object IslandHostFacade {

    class SlotConfig(
        val maxWidthDp: Int,
        val paddingLeftDp: Int,
        val paddingRightDp: Int,
        val isLeft: Boolean
    )

    /**
     * 从宿主传入的 islandData 实例中反射调用 getExtras 并获取包名
     */
    fun extractPackageName(islandData: Any?): String {
        return runCatching {
            val getExtrasMethod = islandData?.javaClass?.methods?.find {
                it.name == "getExtras" && it.parameterTypes.isEmpty() && it.returnType.name.contains("Bundle")
            }
            val extras = getExtrasMethod?.invoke(islandData) as? android.os.Bundle
            extras?.getString("miui.pkg.name")
        }.getOrNull() ?: ""
    }

    /**
     * 打印/日志记录摄像头挖孔位置，用于分离歌词模式。
     */
    private var loggedCutoutInfo = false

    fun logCameraCutoutInfo(rootView: ViewGroup) {
        if (loggedCutoutInfo) return
        val cutoutView = IslandViewHelper.findViewByName(rootView, "area_cutout")
        if (cutoutView != null) {
            val cutoutLoc = IntArray(2)
            cutoutView.getLocationOnScreen(cutoutLoc)
            HookLogger.d("IslandHostFacade", "成功定位摄像头容器(area_cutout), 宽度 = ${cutoutView.width}px, 绝对X = ${cutoutLoc[0]}")
        } else {
            HookLogger.d("IslandHostFacade", "未找到系统 area_cutout 容器，将使用几何居中fallback。")
        }
        loggedCutoutInfo = true
    }

    /**
     * 应用宿主的专辑封面及律动图标等容器的可见性以及边距清除
     */
    fun applyHostSettings(rootView: ViewGroup, prefs: SharedPreferences) {
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

    /**
     * 清理所有已注入的组件，并刷新宿主重绘
     */
    fun clearAndRefresh(rootView: ViewGroup) {
        IslandViewHelper.clearInjectedViews(rootView)
        IslandViewHelper.triggerSystemRelayout(rootView)
    }

    /**
     * 清除所有被注入的组件
     */
    fun clearInjectedViews(rootView: ViewGroup) {
        IslandViewHelper.clearInjectedViews(rootView)
    }

    /**
     * 触发布局刷新
     */
    fun triggerSystemRelayout(rootView: ViewGroup) {
        IslandViewHelper.triggerSystemRelayout(rootView)
    }

    /**
     * 通过资源ID名寻找 View 节点
     */
    fun findViewByName(root: ViewGroup, name: String): View? {
        return IslandViewHelper.findViewByName(root, name)
    }

    /**
     * 读取指定卡槽（左/右）的最大宽度与内边距配置
     */
    fun readSlotConfig(prefs: SharedPreferences, parentName: String): SlotConfig {
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
    fun <T : View> ensureSlotWrapper(
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
    fun hideNativeChildren(rootView: ViewGroup, parentName: String, wrapperView: View) {
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
    fun forceImmediateLayout(rootView: ViewGroup, parentName: String, wrapperView: View, maxWidthDp: Int) {
        val parent = IslandViewHelper.findViewByName(rootView, parentName) as? ViewGroup ?: return
        val container = IslandViewHelper.findViewByName(parent, "island_container_module_text") as? ViewGroup ?: parent
        val density = rootView.resources.displayMetrics.density
        val msW = View.MeasureSpec.makeMeasureSpec((maxWidthDp * density).toInt(), View.MeasureSpec.AT_MOST)
        val msH = View.MeasureSpec.makeMeasureSpec(container.height, if (container.height > 0) View.MeasureSpec.EXACTLY else View.MeasureSpec.UNSPECIFIED)
        wrapperView.measure(msW, msH)
        wrapperView.layout(0, 0, wrapperView.measuredWidth, wrapperView.measuredHeight)
    }

    /**
     * 触发外圈光效绑定注入
     */
    fun injectHostGlow(viewGroup: ViewGroup, islandData: Any?, prefs: SharedPreferences) {
        HookIslandGlow.injectAndTriggerGlow(viewGroup, islandData, prefs)
    }

    /**
     * 更新光效颜色
     */
    fun updateHostGlow(albumArt: Bitmap?, prefs: SharedPreferences) {
        HookIslandGlow.updateMusicGlow(albumArt, prefs)
    }
}
