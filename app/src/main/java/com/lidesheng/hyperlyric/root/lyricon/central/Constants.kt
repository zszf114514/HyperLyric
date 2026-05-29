/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.lidesheng.hyperlyric.root.lyricon.central

import com.lidesheng.hyperlyric.root.lyricon.provider.ProviderConstants

internal object Constants {
    fun isDebug(): Boolean = false

    internal const val ACTION_REGISTER_PROVIDER: String = ProviderConstants.ACTION_REGISTER_PROVIDER
    internal const val ACTION_CENTRAL_BOOT_COMPLETED: String = ProviderConstants.ACTION_CENTRAL_BOOT_COMPLETED
    internal const val EXTRA_BUNDLE: String = ProviderConstants.EXTRA_BUNDLE
    internal const val EXTRA_BINDER: String = ProviderConstants.EXTRA_BINDER
}
