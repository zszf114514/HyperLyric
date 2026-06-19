package com.lidesheng.hyperlyric.utils

data class MigrationItem(
    val text: String,
    val summary: String? = null,
    val url: String? = null
)

data class MigrationNote(
    val versionCode: Int,
    val items: List<MigrationItem>
)

object MigrationData {
    val notes = listOf(
        MigrationNote(
            versionCode = 1932,
            items = listOf(
                MigrationItem(
                    text = "本次更新和xposed模块功能无关，但是使用无 root 模式的请注意",
                    summary = "新版本大幅更改了“通知型灵动岛歌词”的歌词数据来源，默认选择 metadata（自动）即可。当然，你也可以选择指定的歌词源"
                )
            )
        ),
        MigrationNote(
            versionCode = 1931,
            items = listOf(
                MigrationItem(
                    text = "HyperLyric v6.0 往后，需要Lyricon central才可继续使用Lyricon 歌词源",
                    summary = "点击跳转下载 Lyricon central 模块",
                    url = "https://github.com/tomakino/lyricon/releases/tag/core"
                )
            )
        )
    )
}
