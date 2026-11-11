package vn.edu.uit.mobile.model

/** UMA credentials never belong in diagnostics, UI state, navigation or saved-instance state. */
data class UmaSession(val cookie: String, val csrf: String) {
    override fun toString() = "UmaSession(redacted)"
}
