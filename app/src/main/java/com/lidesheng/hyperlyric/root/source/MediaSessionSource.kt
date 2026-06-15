package com.lidesheng.hyperlyric.root.source

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.lidesheng.hyperlyric.lyric.source.LyricSink
import com.lidesheng.hyperlyric.lyric.source.LyricSource
import com.lidesheng.hyperlyric.root.utils.HookLogger

class MediaSessionSource(context: Context) : LyricSource {

    override val id = "mediasession"
    override val displayName = "MediaSession"

    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val trackedControllers = java.util.concurrent.ConcurrentHashMap<MediaController, MediaController.Callback>()
    private val cachedTitles = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var sink: LyricSink? = null
    private var activePkg: String? = null
    private var currentIdentifier: String? = null
    private var cachedSongTitle: String = ""
    private var cachedArtist: String = ""
    private var cachedAlbum: String = ""

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        onActiveSessionsChanged(controllers)
    }

    override fun isAvailable() = true

    override fun start(sink: LyricSink) {
        this.sink = sink
        trackedControllers.clear()
        cachedTitles.clear()
        try {
            manager.addOnActiveSessionsChangedListener(sessionListener, null)
            onActiveSessionsChanged(manager.getActiveSessions(null))
            HookLogger.i("MediaSessionSource", "MediaSession 歌词源已启动")
        } catch (e: Exception) {
            HookLogger.e("MediaSessionSource", "MediaSession 监听初始化失败", e)
        }
    }

    override fun stop() {
        try {
            manager.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
        trackedControllers.forEach { (ctrl, cb) ->
            try { ctrl.unregisterCallback(cb) } catch (_: Exception) {}
        }
        trackedControllers.clear()
        cachedTitles.clear()
        activePkg = null
        currentIdentifier = null
        cachedSongTitle = ""
        cachedArtist = ""
        cachedAlbum = ""
        sink?.onStop()
        sink = null
        HookLogger.i("MediaSessionSource", "MediaSession 歌词源已停止")
    }

    private fun onActiveSessionsChanged(controllers: List<MediaController>?) {
        if (controllers == null) return
        val currentSessions = controllers.toSet()

        // 移除已失效的控制器
        val toRemove = trackedControllers.keys.filter { it !in currentSessions }
        for (dead in toRemove) {
            val cb = trackedControllers.remove(dead)
            if (cb != null) try { dead.unregisterCallback(cb) } catch (_: Exception) {}
        }

        // 注册新控制器
        for (ctrl in controllers) {
            if (!trackedControllers.containsKey(ctrl)) {
                val cb = createCallback(ctrl)
                try {
                    ctrl.registerCallback(cb)
                    trackedControllers[ctrl] = cb
                    syncTitle(ctrl)
                } catch (_: Exception) {}
            }
        }
    }

    private fun createCallback(controller: MediaController): MediaController.Callback {
        return object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                syncTitle(controller)
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                val isPlaying = state?.state == PlaybackState.STATE_PLAYING
                sink?.onPlaybackStateChanged(isPlaying)
                // 播放状态变化时也同步一次 title
                syncTitle(controller)
            }

            override fun onSessionDestroyed() {
                onActiveSessionsChanged(manager.getActiveSessions(null))
            }
        }
    }

    private fun syncTitle(controller: MediaController) {
        val pkg = controller.packageName ?: return
        val metadata = controller.metadata ?: return
        val fullTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.lines()?.firstOrNull { it.isNotBlank() }?.trim() ?: return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val identifier = "$pkg-$fullTitle-$artist-$album-$duration"

        val isNewSong = identifier != currentIdentifier
        if (isNewSong) {
            currentIdentifier = identifier

            // Artist-Title 解析：部分 App 把 "标题 - 艺术家" 放在 artist 字段
            val (identityTitle, identityArtist) = if (artist.contains(" - ")) {
                Pair(artist.substringAfterLast(" - ").trim(), artist.substringBeforeLast(" - ").trim())
            } else {
                Pair(fullTitle, artist)
            }

            cachedSongTitle = identityTitle
            cachedArtist = identityArtist
            cachedAlbum = album

            sink?.onMetadata(title = identityTitle, artist = identityArtist, album = album, publisher = pkg)
            HookLogger.i("MediaSessionSource", "新歌: $identityTitle - $identityArtist")
        }

        // 每次 title 变化都作为歌词推送（保留完整文本含 \n 翻译）
        val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val lyricKey = "${pkg}_lyric"
        if (cachedTitles[lyricKey] != rawTitle) {
            cachedTitles[lyricKey] = rawTitle
            sink?.onPlainText(rawTitle)
        }
    }
}
