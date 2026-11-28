package slussen.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The device permissions of ADR-001 §5 (S-36, S-B-37, NFR-022): the merged manifest of every variant of the app holds
 * exactly these, no more and no fewer: network access, the network state, keeping the device awake while a Scheduled
 * Fetch runs, starting again after the device restarts, showing notifications, and the signature-level permission AndroidX
 * declares for the app's own receivers. The list changes only together with NFR-022 and ADR-001 §5, never on its own to
 * make a build pass; a permission a library brings beyond it is removed in the app's manifest.
 */
object DevicePermissions {
    /** Network access, for the requests to the Data Providers and the Budget App (NFR-022). */
    const val NETWORK = "android.permission.INTERNET"

    /** Starting a Scheduled Fetch only while the device has a network connection (UC-001 A12, BR-013). */
    const val NETWORK_STATE = "android.permission.ACCESS_NETWORK_STATE"

    /** Keeping the device awake while a Scheduled Fetch runs. */
    const val WAKE_LOCK = "android.permission.WAKE_LOCK"

    /** Keeping the Fetch Schedule after the device restarts (UC-001 BR-013). */
    const val AFTER_RESTART = "android.permission.RECEIVE_BOOT_COMPLETED"

    /** Showing the New Transaction Notification (UC-001 BR-014); the device asks the Operator for it. */
    const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    /** The permissions of NFR-022, in the order of ADR-001 §5's table. */
    val listed: List<String> = listOf(NETWORK, NETWORK_STATE, WAKE_LOCK, AFTER_RESTART, NOTIFICATIONS)

    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

    /** The elements of a manifest that ask the device for a permission. */
    private val requesting = setOf("uses-permission", "uses-permission-sdk-23")

    /** AndroidX's signature-level permission for the receivers of the app [applicationId]; it asks the device for nothing. */
    fun receiverPermissionOf(applicationId: String): String = "$applicationId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

    /** Exactly the permissions of ADR-001 §5 for the app [applicationId]. */
    fun allowed(applicationId: String): Set<String> = (listed + receiverPermissionOf(applicationId)).toSet()

    /** Every permission [manifest] asks for, in the order it lists them. */
    fun requested(manifest: String): List<String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(manifest)))
        val elements = document.getElementsByTagName("*")
        return (0 until elements.length)
            .map { elements.item(it) as Element }
            .filter { (it.localName ?: it.tagName) in requesting }
            .map { it.getAttributeNS(ANDROID_NAMESPACE, "name") }
    }

    /** The permissions of [requested] that ADR-001 §5 does not allow for the app [applicationId], each once. */
    fun disallowed(requested: List<String>, applicationId: String): List<String> =
        requested.filterNot { it in allowed(applicationId) }.distinct()

    /** The permissions of ADR-001 §5 for the app [applicationId] that [requested] does not hold, in the table's order. */
    fun missing(requested: List<String>, applicationId: String): List<String> =
        allowed(applicationId).filterNot { it in requested }

    /**
     * The tasks of the Android Gradle Plugin that package the variant [variantName]: the APK, the bundle and its
     * signing, the universal APK (ADR-001 §6.4). `assemble*`, `bundle*` and `install*` reach them; the resource and
     * asset steps that the compile tasks need do not.
     */
    fun packagingTasks(variantName: String): Set<String> {
        val variant = variantName.replaceFirstChar { it.uppercaseChar() }
        return setOf("package$variant", "bundle$variant", "package${variant}Bundle", "sign${variant}Bundle", "package${variant}UniversalApk")
    }
}

/**
 * The permission check of ADR-001 §6.4 (S-36, S-B-37, NFR-022) for one variant: it reads the variant's merged manifest,
 * so a permission that a library brings is seen, and fails naming each `uses-permission` that ADR-001 §5 does not allow
 * and each one of its list that the manifest does not hold. A dependency of every packaging task of the variant and of
 * `check`.
 */
@CacheableTask
abstract class VerifyPermissionsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    /** Records the permissions checked, so that the check is up to date until the merged manifest changes. */
    @get:OutputFile
    abstract val checked: RegularFileProperty

    @TaskAction
    fun verify() {
        val requested = DevicePermissions.requested(mergedManifest.get().asFile.readText())
        val disallowed = DevicePermissions.disallowed(requested, applicationId.get())
        val missing = DevicePermissions.missing(requested, applicationId.get())
        if (disallowed.isNotEmpty() || missing.isNotEmpty()) {
            val more = if (disallowed.isEmpty()) {
                ""
            } else {
                "The merged manifest of the `${variantName.get()}` variant asks the device for permissions that " +
                    "ADR-001 section 5 does not allow (NFR-022):\n" + disallowed.joinToString("\n") { "  - `$it`" } + "\n"
            }
            val fewer = if (missing.isEmpty()) {
                ""
            } else {
                "The merged manifest of the `${variantName.get()}` variant lacks permissions that ADR-001 section 5 " +
                    "lists (NFR-022):\n" + missing.joinToString("\n") { "  - `$it`" } + "\n"
            }
            throw GradleException(
                more + fewer +
                    "Exactly ${DevicePermissions.listed.joinToString(", ") { "`$it`" }} and " +
                    "`<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` are allowed. A permission a library brings " +
                    "is listed in the manifest merger report under build/outputs/logs/ and is removed in the app's " +
                    "manifest; a use case that needs another permission adds it to NFR-022 and to ADR-001 section 5 first.",
            )
        }
        checked.get().asFile.writeText(requested.joinToString("\n", postfix = "\n"))
    }
}
