package com.takeoutfm.shared.util

import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun decompose(s: String): String =
    (s as NSString).decomposedStringWithCanonicalMapping
