package com.lidesheng.hyperlyric.root.bridge

import android.app.Application
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.island.renderer.IslandRenderer
import com.lidesheng.hyperlyric.root.island.renderer.SplitIslandRenderer
import com.lidesheng.hyperlyric.root.island.renderer.StandardIslandRenderer
import com.lidesheng.hyperlyric.root.utils.HookLogger

object IpcRouter {

    private const val TAG = "IpcRouter"

    private fun getActiveRenderer(): IslandRenderer =
        if (HookEntry.activeMode == 1) SplitIslandRenderer else StandardIslandRenderer

    fun initialize(app: Application) {
        LyriconBridge.routing(app) {
            onCommand(AppBridgeConstants.REQUEST_UPDATE_LYRIC_STYLE) {
                HookLogger.d(TAG, "接收到样式更新请求")
                getActiveRenderer().refreshActiveIsland()
            }
            onCommand("com.lidesheng.hyperlyric.REFRESH_ISLAND") {
                HookLogger.d(TAG, "接收到超级岛刷新请求")
                getActiveRenderer().refreshActiveIsland()
            }
            onCommand("com.lidesheng.hyperlyric.UPDATE_LYRIC_ANIM") {
                HookLogger.d(TAG, "接收到歌词动画刷新请求")
                getActiveRenderer().refreshActiveIsland()
            }
        }
    }
}
