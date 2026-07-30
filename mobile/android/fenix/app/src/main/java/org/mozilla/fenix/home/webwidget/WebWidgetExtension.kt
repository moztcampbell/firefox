/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.home.webwidget

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mozilla.components.concept.engine.webextension.WebExtensionRuntime
import mozilla.components.support.base.log.logger.Logger

/**
 * Installs and tracks the built-in web extension that backs the homepage [org.mozilla.fenix.home.webwidget.ui.WebWidget].
 *
 * The page is served from the extension's `moz-extension://<uuid>/` origin, whose UUID is assigned by
 * Gecko at install time; the fully resolved page URL is only known once installation completes and is
 * published through [widgetUrl]. [WIDGET_PAGE] must match the file installed alongside the manifest.
 */
object WebWidgetExtension {
    const val ID = "webwidget@mozac.org"
    const val RESOURCE_URL = "resource://android/assets/extensions/web-widget/"
    const val WIDGET_PAGE = "widget.html"

    private val logger = Logger("WebWidgetExtension")

    private val _widgetUrl = MutableStateFlow<String?>(null)

    /**
     * `moz-extension://<uuid>/widget.html` URL of the widget page, or null until the extension has
     * been installed.
     */
    val widgetUrl: StateFlow<String?> = _widgetUrl.asStateFlow()

    /**
     * Installs the built-in extension in [runtime], publishing its widget page URL through
     * [widgetUrl] on success.
     */
    fun install(runtime: WebExtensionRuntime) {
        runtime.installBuiltInWebExtension(
            id = ID,
            url = RESOURCE_URL,
            onSuccess = { extension ->
                // `optionsPageUrl` is still null in this callback even though the extension is ready,
                // so resolve the page against the extension's `moz-extension://<uuid>/` base URL.
                val baseUrl = extension.getMetadata()?.baseUrl
                if (baseUrl.isNullOrBlank()) {
                    logger.error("WebWidget extension installed without a base URL")
                } else {
                    _widgetUrl.value = "$baseUrl$WIDGET_PAGE"
                }
            },
            onError = { throwable ->
                logger.error("Failed to install WebWidget extension", throwable)
            },
        )
    }
}
