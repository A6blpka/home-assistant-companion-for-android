import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

private val DEFAULT_PACKAGED_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

/**
 * Bundles per-ABI WebView APKs from [webview-apk] into generated assets.
 *
 * Expected layout:
 * ```
 * webview-apk/
 *   arm64-v8a/webview.apk
 *   x86/webview.apk
 * ```
 *
 * The `:automotive` module falls back to `../app/webview-apk` when its own directory is absent.
 */
internal fun Project.configureWebViewApkAssets() {
    val webViewApkRoot = webViewApkSourceDir()?.asFile ?: return

    extensions.configure<ApplicationAndroidComponentsExtension> {
        onVariants { variant ->
            val packagedAbis = resolvePackagedAbis()
            val abisToBundle = packagedAbis.filter { abi ->
                File(webViewApkRoot, "$abi/webview.apk").isFile
            }
            if (abisToBundle.isEmpty()) return@onVariants

            val taskName = "copyWebViewApk${variant.name.replaceFirstChar { it.uppercaseChar() }}"
            val copyTask = tasks.register(taskName, CopyWebViewApkTask::class.java) {
                sourceRoot.set(webViewApkRoot)
                abis.set(abisToBundle)
            }

            variant.sources.assets?.addGeneratedSourceDirectory(
                copyTask,
                CopyWebViewApkTask::outputDirectory,
            )
        }
    }
}

private fun Project.webViewApkSourceDir(): Directory? {
    val local = layout.projectDirectory.dir("webview-apk")
    if (local.asFile.isDirectory) return local
    val fromApp = layout.projectDirectory.dir("../app/webview-apk")
    if (fromApp.asFile.isDirectory) return fromApp
    return null
}

private fun Project.resolvePackagedAbis(): List<String> {
    val requestedAbis = findProperty("abis")
        ?.toString()
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    return requestedAbis.ifEmpty { DEFAULT_PACKAGED_ABIS }
}

internal abstract class CopyWebViewApkTask : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:InputDirectory
    abstract val sourceRoot: DirectoryProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @TaskAction
    fun copyApks() {
        val outputDir = outputDirectory.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        val abisToBundle = abis.get()
        abisToBundle.forEach { abi ->
            val assetName = if (abisToBundle.size == 1) {
                "webview.apk"
            } else {
                "webview-$abi.apk"
            }
            val sourceApk = File(sourceRoot.get().asFile, "$abi/webview.apk")
            check(sourceApk.isFile) { "Missing WebView APK for ABI $abi at ${sourceApk.absolutePath}" }
            sourceApk.copyTo(File(outputDir, assetName), overwrite = true)
        }
    }
}
