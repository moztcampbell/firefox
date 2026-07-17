/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.home.webwidget.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import mozilla.components.browser.errorpages.ErrorType
import mozilla.components.compose.base.menu.DropdownMenu
import mozilla.components.compose.base.menu.MenuItem
import mozilla.components.compose.base.modifier.thenConditional
import mozilla.components.compose.base.text.Text
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.LifecycleObserver
import mozilla.components.concept.engine.request.RequestInterceptor
import mozilla.components.concept.engine.window.WindowRequest
import org.mozilla.fenix.components.components
import org.mozilla.fenix.home.ui.horizontalMargin
import mozilla.components.ui.icons.R as iconsR

// Default proportions for the whole card (header included), matching the desktop crossword widget
// footprint (300px by 250px).
private const val WebWidgetDefaultAspectRatio = 300f / 250f

// Inset around the web view, mirroring the desktop widget card: --space-medium (0.75rem) padding on
// the sides/bottom and a --space-xsmall (0.25rem) gap between the title bar and the content.
private val WebWidgetContentPadding = 12.dp
private val WebWidgetHeaderGap = 4.dp

// Isolates the widget's cookies/storage/permissions in their own persistent container, keeping it
// out of the user's normal browsing partition.
private const val WebWidgetContextId = "web-widget"

// Connectivity-related load failures that should surface the offline error content rather than the
// engine's built-in error page.
private val NetworkErrorTypes = setOf(
    ErrorType.ERROR_NET_INTERRUPT,
    ErrorType.ERROR_NET_TIMEOUT,
    ErrorType.ERROR_NET_RESET,
    ErrorType.ERROR_CONNECTION_REFUSED,
    ErrorType.ERROR_UNKNOWN_HOST,
    ErrorType.ERROR_OFFLINE,
    ErrorType.ERROR_NO_INTERNET,
    ErrorType.ERROR_PROXY_CONNECTION_REFUSED,
    ErrorType.ERROR_UNKNOWN_PROXY_HOST,
)

/**
 * A homepage card that renders [url] inside a dedicated GeckoView. What the widget shows is
 * determined entirely by [url] (e.g. the crossword endpoint). The backing
 * [mozilla.components.concept.engine.EngineSession] is standalone (not linked to a browser tab in
 * the [mozilla.components.browser.state.store.BrowserStore]), so the content never surfaces in the
 * tabs tray.
 *
 * The GeckoView is hosted in a [ScrollPassthroughContainer] so the content stays interactive (like
 * an iframe) while the homepage can still be scrolled by dragging over the widget.
 *
 * @param url The URL to load and render in the widget.
 * @param modifier [Modifier] to apply to the card.
 * @param title Optional title shown in a header row above the content, alongside the overflow menu.
 * When null, no header is shown.
 * @param aspectRatio Width-to-height ratio the whole card (header included) is sized to.
 * @param placeholder Optional content shown over the widget until the page has painted, hiding the
 * initial blank/white GeckoView. When null, the card's surface color is shown instead.
 * @param onOpenLinkInNewTab Invoked with the target URL when the page navigates away from its own
 * origin (a link click or window.open), so the caller can open it in a normal browser tab instead
 * of inside the widget.
 * @param errorContent Optional content shown in place of the page when it fails to load because of
 * a network/connectivity error. It receives an `onRetry` callback that reloads the page.
 * @param onContentFocusChanged Invoked with true when focus moves into the widget's content (e.g. a
 * web input is focused, popping the keyboard) and false when it leaves. Lets the caller react, for
 * instance by scrolling the card into a comfortable position.
 */
@Composable
fun WebWidget(
    url: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    aspectRatio: Float = WebWidgetDefaultAspectRatio,
    placeholder: (@Composable () -> Unit)? = null,
    onOpenLinkInNewTab: (String) -> Unit = {},
    errorContent: (@Composable (onRetry: () -> Unit) -> Unit)? = null,
    onContentFocusChanged: (focused: Boolean) -> Unit = {},
) {
    val engine = components.core.engine
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val currentOnOpenLinkInNewTab by rememberUpdatedState(onOpenLinkInNewTab)
    val currentOnContentFocusChanged by rememberUpdatedState(onContentFocusChanged)

    // Whether [errorContent] should be shown because the visible page failed to load with a
    // connectivity error. [loadErrored] tracks failures within the current load so the decision is
    // only committed when the load finishes, letting the error card stay put across a refresh.
    val networkError = remember(engine, url) { mutableStateOf(false) }
    val loadErrored = remember(engine, url) { mutableStateOf(false) }

    val engineView = remember(engine) { engine.createView(context) }
    val session = remember(engine, url) {
        // A dedicated contextId partitions cookies/storage away from normal browsing, and clearing
        // the history delegate keeps the widget's page out of the user's history.
        engine.createSession(private = false, contextId = WebWidgetContextId).apply {
            settings.historyTrackingDelegate = null

            // Route navigations that leave the widget's origin to a normal browser tab, while
            // delegating everything else (the widget's own content, error pages) to the app's
            // existing interceptor.
            val appInterceptor = settings.requestInterceptor
            settings.requestInterceptor = object : RequestInterceptor {
                override fun onLoadRequest(
                    engineSession: EngineSession,
                    uri: String,
                    lastUri: String?,
                    hasUserGesture: Boolean,
                    isSameDomain: Boolean,
                    isRedirect: Boolean,
                    isDirectNavigation: Boolean,
                    isSubframeRequest: Boolean,
                ): RequestInterceptor.InterceptionResponse? {
                    if (!isSubframeRequest && hasUserGesture && !isSameDomain) {
                        currentOnOpenLinkInNewTab(uri)
                        return RequestInterceptor.InterceptionResponse.Deny
                    }
                    return appInterceptor?.onLoadRequest(
                        engineSession, uri, lastUri, hasUserGesture,
                        isSameDomain, isRedirect, isDirectNavigation, isSubframeRequest,
                    )
                }

                override fun onErrorRequest(
                    session: EngineSession,
                    errorType: ErrorType,
                    uri: String?,
                ): RequestInterceptor.ErrorResponse? {
                    if (errorType in NetworkErrorTypes) {
                        loadErrored.value = true
                        networkError.value = true
                        // Return null so the failed navigation stays in place (no extra about:blank
                        // load that would confuse the per-load bracket); the opaque [errorContent]
                        // overlay hides the engine's own error page.
                        return null
                    }
                    return appInterceptor?.onErrorRequest(session, errorType, uri)
                }

                override fun interceptsAppInitiatedRequests() =
                    appInterceptor?.interceptsAppInitiatedRequests() ?: false
            }

            loadUrl(url)
        }
    }

    // Reveal the content once it has actually painted (first contentful paint), falling back to
    // page-load completion. GeckoView does not surface DOMContentLoaded to the app layer.
    var isLoaded by remember(session) { mutableStateOf(false) }

    val webViewContainer = remember(engineView) {
        ScrollPassthroughContainer(context).apply {
            addView(
                engineView.asView(),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    // Report focus entering/leaving the widget's content. When a web input is focused GeckoView
    // takes Android focus, so a focus change landing inside the container means the widget's page
    // (rather than some other UI) grabbed the keyboard.
    DisposableEffect(webViewContainer) {
        val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            currentOnContentFocusChanged(newFocus.isDescendantOf(webViewContainer))
        }
        val viewTreeObserver = webViewContainer.viewTreeObserver
        viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        onDispose {
            if (viewTreeObserver.isAlive) {
                viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
            }
            currentOnContentFocusChanged(false)
        }
    }

    DisposableEffect(lifecycle, engineView) {
        val observer = LifecycleObserver(engineView)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            engineView.release()
        }
    }

    DisposableEffect(session) {
        val observer = object : EngineSession.Observer {
            override fun onFirstContentfulPaint() {
                isLoaded = true
                networkError.value = loadErrored.value
            }

            override fun onLoadingStateChange(loading: Boolean) {
                if (loading) {
                    // A new load is starting; assume it succeeds until an error says otherwise.
                    // networkError is left untouched so the error card stays put during a refresh.
                    loadErrored.value = false
                } else {
                    isLoaded = true
                    networkError.value = loadErrored.value
                }
            }

            override fun onWindowRequest(windowRequest: WindowRequest) {
                // target="_blank" / window.open() from the widget page opens a normal tab.
                if (windowRequest.type == WindowRequest.Type.OPEN) {
                    currentOnOpenLinkInNewTab(windowRequest.url)
                }
            }
        }
        session.register(observer)
        onDispose {
            session.unregister(observer)
            session.close()
        }
    }

    // In the error state the card wraps [errorContent]'s height (like the sports widget) instead of
    // reserving the full aspect-ratio footprint. The web view stays mounted at full size and keeps
    // painting underneath the opaque error card (its overflow is clipped by the Surface), so a
    // successful reload is revealed instantly without a repaint.
    val currentErrorContent = errorContent
    val inError = networkError.value && currentErrorContent != null

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalMargin),
        shape = MaterialTheme.shapes.large,
        color = Color.White,
    ) {
        // [aspectRatio] sizes the whole card (header included); the web view fills whatever height is
        // left below the header via [weight]. In the error state the card instead wraps the error
        // content, so the ratio and weight are dropped and the web view falls back to a width-derived
        // aspect size to stay finite while it paints underneath.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .thenConditional(Modifier.aspectRatio(aspectRatio)) { !inError },
        ) {
            if (title != null) {
                WebWidgetHeader(
                    title = title,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Layout(
                modifier = Modifier.thenConditional(Modifier.weight(1f)) { !inError },
                content = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .thenConditional(Modifier.aspectRatio(aspectRatio)) { inError }
                            .thenConditional(Modifier.fillMaxHeight()) { !inError }
                            // Inset the web view to match the desktop crossword card, which pads the
                            // iframe by --space-medium on the sides/bottom and leaves a --space-xsmall
                            // gap below the title bar.
                            .padding(
                                start = WebWidgetContentPadding,
                                top = if (title != null) WebWidgetHeaderGap else WebWidgetContentPadding,
                                end = WebWidgetContentPadding,
                                bottom = WebWidgetContentPadding,
                            ),
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { webViewContainer },
                            update = { engineView.render(session) },
                        )

                        LoadingPlaceholder(
                            visible = !isLoaded && !inError,
                            placeholder = placeholder,
                            modifier = Modifier.matchParentSize(),
                        )
                    }

                    if (currentErrorContent != null && networkError.value) {
                        // Reload in place; the bracket logic reveals the content once the load succeeds.
                        currentErrorContent { session.loadUrl(url) }
                    }
                },
            ) { measurables, constraints ->
                val webView = measurables[0].measure(constraints)
                val error = measurables.getOrNull(1)?.measure(constraints)

                // With an error card, size the widget to it and let the (still-painting) web view
                // overflow underneath; otherwise size to the web view.
                val width = error?.width ?: webView.width
                val height = error?.height ?: webView.height
                layout(width, height) {
                    webView.place(0, 0)
                    error?.place(0, 0)
                }
            }
        }
    }
}

/**
 * An opaque overlay shown over the widget while it loads, hiding the initial blank GeckoView. It
 * fades out once [visible] becomes false.
 *
 * @param visible Whether the overlay is shown.
 * @param placeholder Optional content centered within the overlay.
 * @param modifier [Modifier] applied to the overlay; the caller sizes it (e.g. `matchParentSize`).
 */
@Composable
private fun LoadingPlaceholder(
    visible: Boolean,
    placeholder: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = EnterTransition.None,
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            placeholder?.invoke()
        }
    }
}

/**
 * The card's header row: a [title] on the leading edge and the overflow (kebab) menu on the
 * trailing edge, mirroring the desktop crossword widget's title bar.
 *
 * @param title Text shown on the leading edge of the header.
 * @param modifier [Modifier] applied to the header row.
 */
@Composable
private fun WebWidgetHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(start = WebWidgetContentPadding, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = Color.Black,
            style = MaterialTheme.typography.titleMedium,
        )

        WebWidgetMenu()
    }
}

/**
 * A kebab (three-dot) button that opens a context menu anchored to it.
 *
 * @param modifier [Modifier] applied to the menu's anchor.
 */
@Composable
private fun WebWidgetMenu(modifier: Modifier = Modifier) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { menuExpanded = true }) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_ellipsis_vertical_24),
                contentDescription = "More options",
                tint = Color.Black,
            )
        }

        DropdownMenu(
            menuItems = listOf(
                MenuItem.TextItem(
                    text = Text.String("Sample item 1"),
                    onClick = { menuExpanded = false },
                ),
                MenuItem.TextItem(
                    text = Text.String("Sample item 2"),
                    onClick = { menuExpanded = false },
                ),
                MenuItem.TextItem(
                    text = Text.String("Sample item 3"),
                    onClick = { menuExpanded = false },
                ),
            ),
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        )
    }
}

/**
 * A [FrameLayout] host for the GeckoView that refuses to propagate child requests to disallow
 * touch interception.
 *
 * GeckoView is not a NestedScrollingChild and otherwise claims the whole touch stream (calling
 * `requestDisallowInterceptTouchEvent(true)` to keep it), which stops the homepage's Compose
 * `verticalScroll` from ever scrolling when the drag starts on the widget. Swallowing that request
 * leaves the ancestor scroll free to steal vertical drags after touch slop, while taps and
 * horizontal gestures still reach the GeckoView, giving iframe-like interaction.
 */
private class ScrollPassthroughContainer(context: Context) : FrameLayout(context) {
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // Intentionally not propagated to the parent, so the homepage scroll can intercept
        // vertical drags that start over this widget.
    }
}

private tailrec fun View?.isDescendantOf(ancestor: View): Boolean = when {
    this == null -> false
    this === ancestor -> true
    else -> (parent as? View).isDescendantOf(ancestor)
}
