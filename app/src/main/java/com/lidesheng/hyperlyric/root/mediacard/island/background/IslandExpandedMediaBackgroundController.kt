package com.lidesheng.hyperlyric.root.mediacard.island.background

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.mediacard.notification.background.NotificationMediaBackgroundRenderer
import com.lidesheng.hyperlyric.root.mediacard.notification.background.NotificationMediaColorConfig
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal data class IslandExpandedBackgroundTarget(
    val owner: View,
    val expandedView: View,
    val customBackgroundView: View = expandedView,
    val extensionBackgroundView: View? = null,
    val viewportWidth: Int? = null,
    val viewportHeight: Int? = null,
    val transitionContentBounds: Rect? = null,
    val transitionOccludingViews: List<View> = emptyList(),
    val nativeBackgroundViews: List<View> = listOf(expandedView)
)

internal data class IslandExpandedMediaBackgroundHost(
    val target: IslandExpandedBackgroundTarget,
    val holder: Any,
    val miniBar: View?
)

internal interface IslandExpandedMediaBackgroundApi {
    fun getContext(binder: Any): Context
    fun getPackageName(binder: Any): String?
    fun getArtwork(binder: Any): Drawable?
    fun getBackgroundHosts(binder: Any): List<IslandExpandedMediaBackgroundHost>
    fun prepareCustomBackground(target: IslandExpandedBackgroundTarget)
    fun restoreNativeBackground(target: IslandExpandedBackgroundTarget)
    fun applyCustomForeground(holder: Any, colors: NotificationMediaColorConfig)
    fun applyAllCustomForeground(binder: Any, colors: NotificationMediaColorConfig)
}

internal object IslandExpandedMediaBackgroundController {
    private const val TAG = "IslandExpandedMediaBg"
    private val states = Collections.synchronizedMap(WeakHashMap<Any, BinderState>())
    private val renderers = Collections.synchronizedMap(
        WeakHashMap<ClassLoader, NotificationMediaBackgroundRenderer>()
    )
    private val fakeStates = Collections.synchronizedMap(
        WeakHashMap<View, FakeState>()
    )
    private val latestLock = Any()

    @Volatile
    private var module: XposedModule? = null

    @Volatile
    private var executor: ExecutorService = newExecutor()

    private var latestPresentation: LatestPresentation? = null

    private val prefs: SharedPreferences?
        get() = (module as? HookEntry)?.prefs

    fun initialize(xposedModule: XposedModule) {
        module = xposedModule
        if (executor.isShutdown) executor = newExecutor()
    }

    fun isActive(): Boolean {
        return currentStyle() != RootConstants.ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE_DEFAULT
    }

    fun apply(binder: Any, api: IslandExpandedMediaBackgroundApi) {
        if (!isActive()) {
            restore(binder)
            return
        }
        val context = api.getContext(binder)
        val packageName = api.getPackageName(binder) ?: return
        val artwork = api.getArtwork(binder)
        val hosts = api.getBackgroundHosts(binder)
        if (hosts.isEmpty()) return

        val binderState = states.getOrPut(binder) { BinderState(api) }
        binderState.api = api
        val activeViews = hosts.mapTo(HashSet()) { it.target.expandedView }
        binderState.targets.entries.removeAll { (view, state) ->
            if (view in activeViews) return@removeAll false
            state.request.incrementAndGet()
            restoreTarget(state, api)
            true
        }

        hosts.groupBy { it.target.expandedView }.values.forEach { groupedHosts ->
            applyTarget(
                binder,
                binderState,
                groupedHosts,
                context,
                packageName,
                artwork,
                api
            )
        }
    }

    fun restore(binder: Any) {
        val state = states.remove(binder) ?: return
        state.targets.values.forEach { target ->
            target.request.incrementAndGet()
            restoreTarget(target, state.api)
        }
    }

    fun applyFakeTransition(
        target: IslandExpandedBackgroundTarget,
        miniBar: View?,
        api: IslandExpandedMediaBackgroundApi
    ): Boolean {
        if (!isActive()) return false
        val presentation = synchronized(latestLock) {
            latestPresentation?.let { latest ->
                FakePresentation(
                    latest.identity,
                    latest.bitmap.copy(Bitmap.Config.ARGB_8888, true),
                    latest.colors,
                    Rect(latest.visibleSource)
                )
            }
        } ?: return false
        api.prepareCustomBackground(target)
        val view = target.customBackgroundView
        val transitionRoot = target.extensionBackgroundView ?: run {
            presentation.bitmap.recycle()
            return false
        }
        val state = fakeStates[view]
        if (state?.identity == presentation.identity) {
            presentation.bitmap.recycle()
            state.originalOccludingBackgrounds.forEach { (occludingView, _) ->
                occludingView.background = null
            }
            transitionRoot.background = state.transitionBackground
            view.background = null
        } else {
            val originalTransitionBackground = state?.originalTransitionBackground
                ?: transitionRoot.background
            val originalOccludingBackgrounds = state?.originalOccludingBackgrounds
                ?: target.transitionOccludingViews.map { it to it.background }
            originalOccludingBackgrounds.forEach { (occludingView, _) ->
                occludingView.background = null
            }
            val transitionBackground = FakeTransitionBackgroundDrawable(
                presentation.bitmap,
                target.transitionContentBounds
                    ?: descendantBounds(transitionRoot, target.expandedView),
                presentation.visibleSource,
                presentation.colors.backgroundEnd
            )
            transitionRoot.background = transitionBackground
            view.background = null
            fakeStates.put(
                view,
                FakeState(
                    presentation.identity,
                    transitionBackground,
                    presentation.bitmap,
                    target,
                    api,
                    originalTransitionBackground,
                    originalOccludingBackgrounds
                )
            )
                ?.bitmap
                ?.takeUnless(Bitmap::isRecycled)
                ?.recycle()
        }
        miniBar?.backgroundTintList = ColorStateList.valueOf(
            presentation.colors.textPrimary.withAlpha(0x99)
        )
        return true
    }

    fun applyForeground(binder: Any, api: IslandExpandedMediaBackgroundApi) {
        val colors = states[binder]
            ?.targets
            ?.values
            ?.firstNotNullOfOrNull(TargetState::colors)
            ?: return
        api.applyAllCustomForeground(binder, colors)
    }

    fun onExpandedViewShown(view: View) {
        if (!isActive()) return
        val matches = synchronized(states) {
            states.entries.mapNotNull { (binder, binderState) ->
                binderState.targets.values.firstOrNull {
                    it.target.expandedView === view
                }?.let { targetState ->
                    ExpandedTargetBinding(binder, binderState, targetState)
                }
            }
        }
        if (matches.isEmpty()) return
        view.postOnAnimation {
            matches.forEach { binding ->
                if (states[binding.binder] !== binding.binderState || !isActive()) {
                    return@forEach
                }
                if (binding.targetState.appliedStyle == currentStyle()) {
                    forceBackgroundAttached(binding.targetState, binding.binderState.api)
                }
                apply(binding.binder, binding.binderState.api)
            }
        }
    }

    fun onNativeBackgroundUpdated(view: View) {
        if (!isActive()) return
        val binderSnapshot = synchronized(states) { states.values.toList() }
        binderSnapshot.forEach { binderState ->
            binderState.targets.values.forEach { targetState ->
                if (targetState.target.nativeBackgroundViews.any { it === view }) {
                    forceBackgroundAttached(targetState, binderState.api)
                }
            }
        }
        val fakeSnapshot = synchronized(fakeStates) { fakeStates.values.toList() }
        fakeSnapshot.forEach { state ->
            if (state.target.nativeBackgroundViews.any { it === view }) {
                state.api.prepareCustomBackground(state.target)
                state.originalOccludingBackgrounds.forEach { (occludingView, _) ->
                    occludingView.background = null
                }
                state.target.extensionBackgroundView?.background = state.transitionBackground
                state.target.customBackgroundView.background = null
                state.target.customBackgroundView.invalidate()
                state.target.extensionBackgroundView?.invalidate()
            }
        }
    }

    fun restoreFakeTransition(
        target: IslandExpandedBackgroundTarget,
        api: IslandExpandedMediaBackgroundApi
    ) {
        val state = fakeStates.remove(target.customBackgroundView) ?: return
        state.target.extensionBackgroundView?.background = state.originalTransitionBackground
        state.originalOccludingBackgrounds.forEach { (view, background) ->
            view.background = background
        }
        api.restoreNativeBackground(target)
        state.bitmap.takeUnless(Bitmap::isRecycled)?.recycle()
    }

    fun releaseAll() {
        executor.shutdownNow()
        val snapshot = synchronized(states) { states.values.toList() }
        val fakeSnapshot = synchronized(fakeStates) { fakeStates.values.toList() }
        val cleanup = Runnable {
            snapshot.forEach { state ->
                state.targets.values.forEach { target ->
                    target.request.incrementAndGet()
                    restoreTarget(target, state.api)
                }
            }
            fakeSnapshot.forEach { state ->
                state.target.extensionBackgroundView?.background =
                    state.originalTransitionBackground
                state.originalOccludingBackgrounds.forEach { (view, background) ->
                    view.background = background
                }
                state.api.restoreNativeBackground(state.target)
                if (!state.bitmap.isRecycled) state.bitmap.recycle()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
        else Handler(Looper.getMainLooper()).post(cleanup)
        states.clear()

        val rendererSnapshot = synchronized(renderers) { renderers.values.toList() }
        rendererSnapshot.forEach(NotificationMediaBackgroundRenderer::close)
        renderers.clear()
        fakeStates.clear()
        synchronized(latestLock) {
            latestPresentation?.bitmap?.recycle()
            latestPresentation = null
        }
    }

    private fun applyTarget(
        binder: Any,
        binderState: BinderState,
        hosts: List<IslandExpandedMediaBackgroundHost>,
        context: Context,
        packageName: String,
        artwork: Drawable?,
        api: IslandExpandedMediaBackgroundApi
    ) {
        val host = hosts.first()
        val view = host.target.expandedView
        val targetState = binderState.targets.getOrPut(view) {
            TargetState(
                target = host.target,
                miniBar = host.miniBar,
                originalMiniBarTint = host.miniBar?.backgroundTintList
            )
        }
        targetState.target = host.target
        targetState.miniBar = host.miniBar
        val width = host.target.viewportWidth?.takeIf { it > 0 }
            ?: view.width.takeIf { it > 0 }
            ?: view.measuredWidth.takeIf { it > 0 }
        val height = host.target.viewportHeight?.takeIf { it > 0 }
            ?: view.height.takeIf { it > 0 }
            ?: view.measuredHeight.takeIf { it > 0 }
        if (width == null || height == null) {
            if (!targetState.layoutPending) {
                targetState.layoutPending = true
                view.post {
                    targetState.layoutPending = false
                    if (states[binder] === binderState && isActive()) apply(binder, api)
                }
            }
            return
        }

        val style = currentStyle()
        if (
            targetState.customApplied && targetState.appliedStyle != null &&
            targetState.appliedStyle != style && !view.isShown
        ) {
            targetState.request.incrementAndGet()
            targetState.token = null
            targetState.renderPending = false
            return
        }
        val blurAmount = currentBlurAmount()
        val autoInvert = currentAutoInvert()
        val token = "$style:$blurAmount:$autoInvert:$packageName:$width:$height"
        if (
            targetState.customApplied && targetState.token == token &&
            targetState.lastArtwork === artwork
        ) {
            ensureBackgroundAttached(targetState, api)
            targetState.colors?.let { colors ->
                hosts.forEach { api.applyCustomForeground(it.holder, colors) }
                targetState.miniBar?.backgroundTintList = ColorStateList.valueOf(
                    colors.textPrimary.withAlpha(0x99)
                )
            }
            return
        }

        targetState.token = token
        targetState.lastArtwork = artwork
        targetState.renderPending = true
        val request = targetState.request.incrementAndGet()
        val classLoader = binder.javaClass.classLoader ?: return
        val renderer = synchronized(renderers) {
            renderers.getOrPut(classLoader) { NotificationMediaBackgroundRenderer(classLoader) }
        }

        executor.execute {
            val renderHeight = if (
                style == RootConstants.ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE_LINEAR_GRADIENT
            ) {
                height
            } else {
                maxOf(width, height)
            }
            val rendered = runCatching {
                renderer.renderDrawable(
                    context,
                    artwork,
                    packageName,
                    style,
                    blurAmount,
                    autoInvert,
                    width,
                    renderHeight
                )
            }.onFailure { error ->
                HookLogger.e(TAG, "Failed to render expanded media background", error)
            }.getOrNull()
            if (rendered == null) {
                view.post {
                    if (states[binder] === binderState && targetState.request.get() == request) {
                        targetState.renderPending = false
                    }
                }
                return@execute
            }

            view.post {
                if (
                    states[binder] !== binderState || targetState.request.get() != request ||
                    currentStyle() != style || !isActive()
                ) {
                    rendered.bitmap.recycle()
                    return@post
                }
                if (
                    targetState.customApplied && targetState.appliedToken == token &&
                    targetState.artworkFingerprint == rendered.artworkFingerprint
                ) {
                    rendered.bitmap.recycle()
                    ensureBackgroundAttached(targetState, api)
                    hosts.forEach { api.applyCustomForeground(it.holder, rendered.colors) }
                    targetState.miniBar?.backgroundTintList = ColorStateList.valueOf(
                        rendered.colors.textPrimary.withAlpha(0x99)
                    )
                    targetState.colors = rendered.colors
                    targetState.renderPending = false
                    return@post
                }

                api.prepareCustomBackground(targetState.target)
                setBackground(
                    targetState,
                    rendered.bitmap,
                    currentColorAnimation() && targetState.appliedStyle == style
                )
                hosts.forEach { api.applyCustomForeground(it.holder, rendered.colors) }
                targetState.miniBar?.backgroundTintList = ColorStateList.valueOf(
                    rendered.colors.textPrimary.withAlpha(0x99)
                )
                targetState.customApplied = true
                targetState.appliedStyle = style
                targetState.appliedToken = token
                targetState.artworkFingerprint = rendered.artworkFingerprint
                targetState.colors = rendered.colors
                targetState.renderPending = false
                updateLatest(
                    token,
                    rendered.artworkFingerprint,
                    rendered.bitmap,
                    rendered.colors,
                    width,
                    height
                )
            }
        }
    }

    private fun restoreTarget(
        state: TargetState,
        api: IslandExpandedMediaBackgroundApi
    ) {
        state.miniBar?.backgroundTintList = state.originalMiniBarTint
        if (state.customApplied) api.restoreNativeBackground(state.target)
        state.customApplied = false
        state.appliedStyle = null
        state.renderPending = false
        state.appliedToken = null
        state.artworkFingerprint = null
        state.colors = null
        state.background = null
        state.transitionBackground = null
    }

    private fun ensureBackgroundAttached(
        state: TargetState,
        api: IslandExpandedMediaBackgroundApi
    ) {
        val background = state.background ?: return
        val view = state.target.customBackgroundView
        val current = view.background
        if (current === background || current === state.transitionBackground) return
        api.prepareCustomBackground(state.target)
        view.background = background
        state.transitionBackground = null
    }

    private fun forceBackgroundAttached(
        state: TargetState,
        api: IslandExpandedMediaBackgroundApi
    ) {
        val background = state.background ?: return
        api.prepareCustomBackground(state.target)
        val view = state.target.customBackgroundView
        view.background = background
        state.transitionBackground = null
        view.invalidate()
    }

    private fun setBackground(state: TargetState, bitmap: Bitmap, animate: Boolean) {
        val view = state.target.customBackgroundView
        val width = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)
        val next = FixedViewportBitmapDrawable(
            bitmap,
            state.target.viewportWidth?.takeIf { it > 0 } ?: width,
            state.target.viewportHeight?.takeIf { it > 0 } ?: height
        ).apply {
            setBounds(0, 0, width, height)
        }
        state.background = next
        if (!animate || !view.isShown || !view.isAttachedToWindow) {
            view.background = next
            state.transitionBackground = null
            view.invalidate()
            return
        }
        val previous = view.background
        if (previous == null) {
            view.background = next
            state.transitionBackground = null
            return
        }
        val transition = TransitionDrawable(arrayOf(previous, next)).apply {
            isCrossFadeEnabled = true
            setBounds(0, 0, width, height)
        }
        state.transitionBackground = transition
        view.background = transition
        transition.startTransition(333)
        view.postDelayed({
            if (view.background === transition) {
                next.setBounds(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
                view.background = next
                view.invalidate()
            }
            if (state.transitionBackground === transition) state.transitionBackground = null
        }, 350L)
        view.postOnAnimation {
            view.background?.setBounds(
                0,
                0,
                view.width.coerceAtLeast(1),
                view.height.coerceAtLeast(1)
            )
            view.invalidate()
        }
    }

    private fun updateLatest(
        token: String,
        fingerprint: Long,
        bitmap: Bitmap,
        colors: NotificationMediaColorConfig,
        viewportWidth: Int,
        viewportHeight: Int
    ) {
        val identity = "$token:$fingerprint"
        synchronized(latestLock) {
            if (latestPresentation?.identity == identity) return
            val cacheBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val visibleSource = centerCropSourceRect(
                cacheBitmap.width,
                cacheBitmap.height,
                viewportWidth,
                viewportHeight
            )
            latestPresentation?.bitmap?.recycle()
            latestPresentation = LatestPresentation(identity, cacheBitmap, colors, visibleSource)
        }
    }

    private fun descendantBounds(root: View, descendant: View): Rect {
        val rootLocation = IntArray(2)
        val descendantLocation = IntArray(2)
        root.getLocationInWindow(rootLocation)
        descendant.getLocationInWindow(descendantLocation)
        val left = descendantLocation[0] - rootLocation[0]
        val top = descendantLocation[1] - rootLocation[1]
        return Rect(left, top, left + descendant.width, top + descendant.height)
    }

    private fun centerCropSourceRect(
        bitmapWidth: Int,
        bitmapHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Rect {
        return if (bitmapWidth.toLong() * targetHeight > bitmapHeight.toLong() * targetWidth) {
            val sourceWidth = (bitmapHeight.toLong() * targetWidth / targetHeight)
                .toInt()
                .coerceIn(1, bitmapWidth)
            val left = (bitmapWidth - sourceWidth) / 2
            Rect(left, 0, left + sourceWidth, bitmapHeight)
        } else {
            val sourceHeight = (bitmapWidth.toLong() * targetHeight / targetWidth)
                .toInt()
                .coerceIn(1, bitmapHeight)
            val top = (bitmapHeight - sourceHeight) / 2
            Rect(0, top, bitmapWidth, top + sourceHeight)
        }
    }

    private fun currentStyle(): Int = prefs?.getInt(
        RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE,
        RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE
    )?.coerceIn(
        RootConstants.ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE_DEFAULT,
        RootConstants.ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE_LINEAR_GRADIENT
    ) ?: RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE

    private fun currentBlurAmount(): Int = prefs?.getInt(
        RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_BLUR,
        RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_BLUR
    )?.coerceIn(1, 20) ?: RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_BLUR

    private fun currentAutoInvert(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_AUTO_INVERT,
        RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_AUTO_INVERT
    ) ?: RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_AUTO_INVERT

    private fun currentColorAnimation(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_COLOR_ANIMATION,
        RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_COLOR_ANIMATION
    ) ?: RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_COLOR_ANIMATION

    private fun Int.withAlpha(alpha: Int): Int {
        return this and 0x00ffffff or (alpha.coerceIn(0, 255) shl 24)
    }

    private fun newExecutor(): ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HyperLyric-IslandMediaBackground").apply { isDaemon = true }
    }

    private data class BinderState(
        var api: IslandExpandedMediaBackgroundApi,
        val targets: MutableMap<View, TargetState> = WeakHashMap()
    )

    private data class ExpandedTargetBinding(
        val binder: Any,
        val binderState: BinderState,
        val targetState: TargetState
    )

    private data class TargetState(
        var target: IslandExpandedBackgroundTarget,
        var miniBar: View?,
        val originalMiniBarTint: ColorStateList?,
        var token: String? = null,
        var appliedToken: String? = null,
        var artworkFingerprint: Long? = null,
        var lastArtwork: Drawable? = null,
        var colors: NotificationMediaColorConfig? = null,
        var background: Drawable? = null,
        var transitionBackground: Drawable? = null,
        var customApplied: Boolean = false,
        var appliedStyle: Int? = null,
        var renderPending: Boolean = false,
        var layoutPending: Boolean = false,
        val request: AtomicInteger = AtomicInteger()
    )

    private data class LatestPresentation(
        val identity: String,
        val bitmap: Bitmap,
        val colors: NotificationMediaColorConfig,
        val visibleSource: Rect
    )

    private data class FakePresentation(
        val identity: String,
        val bitmap: Bitmap,
        val colors: NotificationMediaColorConfig,
        val visibleSource: Rect
    )

    private data class FakeState(
        val identity: String,
        val transitionBackground: Drawable,
        val bitmap: Bitmap,
        val target: IslandExpandedBackgroundTarget,
        val api: IslandExpandedMediaBackgroundApi,
        val originalTransitionBackground: Drawable?,
        val originalOccludingBackgrounds: List<Pair<View, Drawable?>>
    )

    private class FixedViewportBitmapDrawable(
        private val bitmap: Bitmap,
        viewportWidth: Int,
        viewportHeight: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val viewportWidth = viewportWidth.coerceAtLeast(1)
        private val viewportHeight = viewportHeight.coerceAtLeast(1)
        private val source = centerCropSourceRect(
            bitmap.width,
            bitmap.height,
            this.viewportWidth,
            this.viewportHeight
        )
        private val destination = Rect()

        override fun draw(canvas: Canvas) {
            if (bitmap.isRecycled || bounds.isEmpty) return
            val destinationHeight = (
                bounds.width().toLong() * viewportHeight / viewportWidth
                ).toInt().coerceIn(1, bounds.height())
            destination.set(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.top + destinationHeight
            )
            canvas.drawBitmap(bitmap, source, destination, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = bitmap.width

        override fun getIntrinsicHeight(): Int = bitmap.height
    }

    private class FakeTransitionBackgroundDrawable(
        private val bitmap: Bitmap,
        private val contentBounds: Rect,
        initialSource: Rect,
        private val fallbackColor: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val fillPaint = Paint().apply { color = fallbackColor }
        private val visibleSource = Rect(initialSource)
        private val source = Rect()
        private val destination = Rect()
        private val bitmapDestination = Rect()

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val left = contentBounds.left.coerceIn(bounds.left, bounds.right)
            val right = contentBounds.right.coerceIn(bounds.left, bounds.right)
            val top = contentBounds.top.coerceIn(bounds.top, bounds.bottom)
            if (left >= right || top >= bounds.bottom) return
            destination.set(left, top, right, bounds.bottom)
            canvas.drawRect(destination, fillPaint)
            if (bitmap.isRecycled || destination.width() <= 0 || destination.height() <= 0) return

            val availableSourceHeight = bitmap.height - visibleSource.top
            if (availableSourceHeight <= 0) return
            val sourceHeight = (
                destination.height().toLong() * visibleSource.width() / destination.width()
                ).toInt().coerceIn(1, availableSourceHeight)
            val destinationHeight = (
                sourceHeight.toLong() * destination.width() / visibleSource.width()
                ).toInt().coerceIn(1, destination.height())
            source.set(
                visibleSource.left,
                visibleSource.top,
                visibleSource.right,
                visibleSource.top + sourceHeight
            )
            bitmapDestination.set(
                destination.left,
                destination.top,
                destination.right,
                destination.top + destinationHeight
            )
            canvas.drawBitmap(bitmap, source, bitmapDestination, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            fillPaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    }
}
