package com.lidesheng.hyperlyric.root.island

import android.view.ViewGroup
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.HookIslandGlow
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.renderer.SplitIslandRenderer
import com.lidesheng.hyperlyric.root.island.renderer.StandardIslandRenderer
import com.lidesheng.hyperlyric.root.utils.DynamicFinder
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule

/**
 * 统一的 SystemUI 挂钩注册与拦截回调转发中心。
 */
object SystemUIHookRegistry {
    lateinit var module: XposedModule

    private val hookedClassLoaders = java.util.Collections.newSetFromMap(java.util.WeakHashMap<ClassLoader, Boolean>())

    @Volatile
    var isHookedSuccess = false
        private set

    fun hook(xposedModule: XposedModule, cl: ClassLoader) {
        if (cl.javaClass.name.contains("BootClassLoader")) return
        if (!hookedClassLoaders.add(cl)) return
        
        module = xposedModule
        
        val islandPkg = "miui.systemui.dynamicisland"

        val contentViewClass = runCatching { 
            cl.loadClass("$islandPkg.window.content.DynamicIslandContentView")
        }.getOrNull() ?: DynamicFinder.findClassByConstantString(cl, "$islandPkg.window.content", "DynamicIslandContentView") ?: return

        runCatching {
            val hookedMethods = mutableListOf<String>()
            
            contentViewClass.methods.filter { it.name == "updateBigIslandView" }.forEach { method ->
                runCatching {
                    module.deoptimize(method)
                    module.hook(method).intercept(UpdateBigIslandHooker())
                    hookedMethods.add("updateBigIslandView")
                    HookLogger.i("SystemUIHookRegistry","已注入超级岛: $method")
                }
            }

            contentViewClass.methods.filter { it.name == "calculateBigIslandWidth" }.forEach { method ->
                runCatching {
                    module.deoptimize(method)
                    module.hook(method).intercept(PreInjectHooker())
                    hookedMethods.add("calculateBigIslandWidth")
                    HookLogger.i("SystemUIHookRegistry","已注入超级岛: $method")
                }
            }

            HookIslandGlow.init(module, cl)

            if (hookedMethods.isNotEmpty()) {
                isHookedSuccess = true
                val methodsSummary = hookedMethods.distinct().joinToString(", ")
                HookLogger.i("SystemUIHookRegistry","初始化完成。已注入超级岛: [$methodsSummary]")
            }
        }.onFailure { e ->
            if (e !is ClassNotFoundException) {
                HookLogger.e("SystemUIHookRegistry", "ModuleInit : 注入超级岛失败", e)
            }
        }
    }

    class PreInjectHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val renderer = if (HookEntry.activeMode == 1) SplitIslandRenderer else StandardIslandRenderer
            return renderer.onPreInject(chain)
        }
    }

    class UpdateBigIslandHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            runCatching {
                val viewGroup = chain.thisObject as? ViewGroup
                val prefs = (module as? HookEntry)?.prefs
                if (viewGroup != null && prefs != null && prefs.getBoolean(RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND, RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND)) {
                    val activePkg = LyriconDataBridge.activePackageName
                    if (!activePkg.isNullOrEmpty()) {
                        val mediaInfo = MediaMetadataHelper.getMediaInfo(viewGroup.context, activePkg)
                        HookIslandGlow.updateMusicGlow(mediaInfo.albumArt, prefs)
                    }
                }
            }

            val renderer = if (HookEntry.activeMode == 1) SplitIslandRenderer else StandardIslandRenderer
            return renderer.onUpdateBigIsland(chain)
        }
    }
}
