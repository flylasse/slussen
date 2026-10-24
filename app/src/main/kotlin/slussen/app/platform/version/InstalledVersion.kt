package slussen.app.platform.version

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import slussen.app.ui.AppVersion

/**
 * The version of the installed package, from Android's package information (ADR-005 §4): the version name the build
 * took from `VERSION`, and the package name, whose `.debug` suffix marks the `debug` variant (ADR-005 §5).
 */
fun Context.installedAppVersion(): AppVersion = AppVersion.of(packageName, packageInfo().versionName)

private fun Context.packageInfo(): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }
