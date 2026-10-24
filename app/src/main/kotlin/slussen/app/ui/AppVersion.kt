package slussen.app.ui

/**
 * The build the Operator runs (ADR-005 §4, S-37): its version name, which the build takes from `VERSION`, as Android's
 * package information gives it, and whether it is the `debug` variant (ADR-005 §5). The composition root reads it from
 * the device; the ViewModel tests supply it.
 */
data class AppVersion(val versionName: String, val debugBuild: Boolean) {
    companion object {
        /** The `applicationIdSuffix` of the `debug` variant (ADR-005 §5). */
        const val DEBUG_SUFFIX = ".debug"

        /** The version of the package [packageName], a debug build when its name ends with [DEBUG_SUFFIX]. */
        fun of(packageName: String, versionName: String?): AppVersion =
            AppVersion(versionName.orEmpty(), debugBuild = packageName.endsWith(DEBUG_SUFFIX))
    }
}
