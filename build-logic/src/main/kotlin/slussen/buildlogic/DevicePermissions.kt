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
 * The device permissions of ADR-001 §5 (S-36, NFR-022): the merged manifest of every variant of the app may ask only for
 * network access and for the signature-level permission AndroidX declares for the app's own receivers.
 */
object DevicePermissions {
    /** Network access, for the requests to the Data Providers and the Budget App (NFR-022). */
    const val NETWORK = "android.permission.INTERNET"

    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

    /** The elements of a manifest that ask the device for a permission. */
    private val requesting = setOf("uses-permission", "uses-permission-sdk-23")

    /** AndroidX's signature-level permission for the receivers of the app [applicationId]; it asks the device for nothing. */
    fun receiverPermissionOf(applicationId: String): String = "$applicationId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

    fun allowed(applicationId: String): Set<String> = setOf(NETWORK, receiverPermissionOf(applicationId))

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
 * The permission check of ADR-001 §6.4 (S-36, NFR-022) for one variant: it reads the variant's merged manifest, so a
 * permission that a library brings is seen, and fails naming each `uses-permission` that ADR-001 §5 does not allow. A
 * dependency of every packaging task of the variant and of `check`.
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
        if (disallowed.isNotEmpty()) {
            throw GradleException(
                "The merged manifest of the `${variantName.get()}` variant asks the device for permissions that " +
                    "ADR-001 section 5 does not allow (NFR-022):\n" +
                    disallowed.joinToString("\n") { "  - `$it`" } + "\n" +
                    "Only `${DevicePermissions.NETWORK}` and `<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` " +
                    "are allowed. A permission a library brings is listed in the manifest merger report under " +
                    "build/outputs/logs/; a use case that needs another permission adds it to NFR-022 and to ADR-001 " +
                    "section 5 first.",
            )
        }
        checked.get().asFile.writeText(requested.joinToString("\n", postfix = "\n"))
    }
}
