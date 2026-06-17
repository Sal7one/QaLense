package com.qalens

import timber.log.Timber

/**
 * Plant this tree to mirror Timber logs into the QaLens event stream, where they join the timeline,
 * reports, and recorded sessions:
 *
 *   if (BuildConfig.DEBUG) Timber.plant(QaLensTimberTree())
 *
 * Only log metadata (level, tag, message, exception class/message) is captured, and everything is
 * redaction-aware at export time.
 */
class QaLensTimberTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val full = if (t != null) "$message — ${t.javaClass.simpleName}: ${t.message}" else message
        QaLens.timberLog(priority, tag, full)
    }
}
