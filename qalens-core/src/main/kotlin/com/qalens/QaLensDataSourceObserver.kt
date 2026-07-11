package com.qalens

/**
 * A6: Interface for observing Room / DataStore changes from the QaLens overlay.
 *
 * Consumer apps implement this interface and register it via [QaLens.registerDataSourceObserver].
 * The overlay uses the observer to react to data-layer changes without depending on Room
 * or DataStore at compile time (the implementations live in the host app).
 */
interface DataSourceObserver {
    /** Called when a Room table or DataStore file changes. */
    fun onChanged(source: String, tableName: String, changeType: ChangeType)

    /** Called when the data source is unavailable (e.g. database locked). */
    fun onError(source: String, error: String)
}

enum class ChangeType {
    INSERT, UPDATE, DELETE, QUERY, OTHER
}
