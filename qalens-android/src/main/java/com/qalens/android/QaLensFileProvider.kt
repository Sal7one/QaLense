package com.qalens.android

import androidx.core.content.FileProvider

/** Dedicated subclass so the library's FileProvider doesn't conflict with the app's own. */
class QaLensFileProvider : FileProvider()
