package com.lidesheng.hyperlyric.root.island.provider

import android.content.SharedPreferences
import android.view.ViewGroup
import com.lidesheng.hyperlyric.root.island.renderer.BaseIslandRenderer

/**
 * 灵动岛卡槽内容填充策略接口。
 */
interface IslandSlotContentProvider {
    fun inject(
        renderer: BaseIslandRenderer,
        rootView: ViewGroup,
        parentName: String,
        tag: String,
        prefs: SharedPreferences,
        pkgName: String,
        mode: Int
    )
}
