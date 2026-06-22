package com.lidesheng.hyperlyric.root.island.renderer

import io.github.libxposed.api.XposedInterface.Chain

/**
 * 灵动岛渲染器接口，规范了宿主事件回调与渲染更新契约。
 */
interface IslandRenderer {
    fun refreshActiveIsland()
    fun updateLyricLine()
    fun updatePosition(position: Long)
    fun onPlaybackStateChanged(isPlaying: Boolean)
    fun clearAllViews()

    /**
     * 宿主拦截到大岛宽度计算时的回调（预注入卡槽）
     */
    fun onPreInject(chain: Chain): Any?

    /**
     * 宿主拦截到大岛视图更新时的回调（更新/挂载视图内容与配置）
     */
    fun onUpdateBigIsland(chain: Chain): Any?
}
