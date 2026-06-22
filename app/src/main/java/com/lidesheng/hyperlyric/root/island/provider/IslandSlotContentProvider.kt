package com.lidesheng.hyperlyric.root.island.provider

import android.content.SharedPreferences
import android.view.ViewGroup

/**
 * 超级岛卡槽内容填充策略接口。
 */
interface IslandSlotContentProvider {
    fun inject(
        rootView: ViewGroup,
        parentName: String,
        tag: String,
        prefs: SharedPreferences,
        pkgName: String,
        mode: Int
    )
}
