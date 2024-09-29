package com.ypwang.plugin.platform

import com.goide.project.GoApplicationLibrariesService
import com.goide.project.GoProjectLibrariesService
import com.goide.sdk.GoSdkService
import com.goide.sdk.GoSdkUtil
import com.goide.vgo.configuration.VgoProjectSettings
import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.EnvironmentUtil
import com.ypwang.plugin.*
import com.ypwang.plugin.model.GithubRelease
import com.ypwang.plugin.model.RunProcessResult
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Paths
import java.util.*

/*  |   Platform  | Host OS | Running OS |
    |-------------|---------|------------|
    |   Windows   | Windows |   Windows  |
    |    Linux    |  Linux  |    Linux   |
    | Mac(darwin) |   Mac   |     Mac    |
    |     WSL     | Windows |    Linux   |
*/
abstract class Platform(protected val project: Project) {
    companion object {
        fun platformFactory(project: Project): Platform =
            when {
                SystemInfo.isWindows -> {
                    GoSdkService.getInstance(project).getSdk(null).sdkRoot?.path    // $GOROOT/bin
                        ?.let(WslPath::getDistributionByWindowsUncPath)                     // WSL distribution
                        ?.let { WSL(project, it) }
                        ?: Windows(project)
                }
                SystemInfo.isLinux -> Linux(project)
                SystemInfo.isMac -> Mac(project)
                else -> throw Exception("Unknown system type: ${SystemInfo.OS_NAME}")
            }

        const val LinterName = "golangci-lint"

        // static util functions =======================================================================================
        // copy stream with progress
        // nio should be more efficient, but let's show some progress to make programmer happy
        @JvmStatic
        protected fun copy(input: InputStream, to: String, totalSize: Long, setFraction: (Double) -> Unit, cancelled: () -> Boolean) {
            FileOutputStream(to).use { fos ->
                var sum = 0.0
                var len: Int
                val data = ByteArray(20 * 1024)

                while (!cancelled()) {
                    len = input.read(data)
                    if (len == -1)
                        break
                    fos.write(data, 0, len)
                    sum += len
                    setFraction(minOf(sum / totalSize, 1.0))
                }
            }
        }

        // get OS arch, host OS === runnig OS
        @JvmStatic
        protected fun arch(): String = System.getProperty("os.arch").let {
            when (it) {
                "x86" -> "386"
                "amd64", "x86_64" -> "amd64"
                "aarch64" -> "arm64"
                else -> throw Exception("Unknown system arch: $it")
            }
        }

        // get IDE & System wide environment variables ========================================
        // get GOROOT bin path from IDE, combine with system PATH
        fun combinePath(project: Project, idePathConverter: ((String) -> String)?, path: String): List<String> {
            val rst = mutableListOf<String>()
            // IDE GOROOT should take precedence
            GoSdkService.getInstance(project).getSdk(null).executable?.path     // go executable path
                ?.let(Paths::get)?.parent?.toString()                                   // get parent folder path
                ?.let{rst.add(idePathConverter?.invoke(it) ?: it)}                      // OS dependent path converting

            if (path.isNotBlank())
                // system path itself is absolute for running OS
                rst.add(path)

            return rst
        }

        // get GOPATH from IDE, combine with system GOPATH
        fun combineGoPath(project: Project, idePathConverter: ((String) -> String)?): List<String> =
            mutableListOf<String>().apply {
                val goPluginSettings = GoProjectLibrariesService.getInstance(project)
                // IDE Project GOPATH > IDE Global GOPATH > System GOPATH
                this.addAll(goPluginSettings.libraryRootUrls)
                this.addAll(GoApplicationLibrariesService.getInstance().libraryRootUrls)
                if (goPluginSettings.isUseGoPathFromSystemEnvironment)
                    // + System GOPATH
                    this.addAll(GoSdkUtil.getGoPathsRootsFromEnvironment().map { it.url })
            }.map { Paths.get(VirtualFileManager.extractPath(it)).toString() }   // IDE path to host OS path
            .map { idePathConverter?.invoke(it) ?: it }                         // OS dependent path converting

        // IDE GO111MODULE or system GO111MODULE
        fun combineModuleOn(project: Project, env: String): String =
            if (VgoProjectSettings.getInstance(project).isIntegrationEnabled || Objects.equals("on", env)) "on" else "off"

        // immutable in current idea process, for host OS ==============================================================
        private val systemPath = EnvironmentUtil.getValue(Const_Path) ?: ""
        private val systemModuleOn = EnvironmentUtil.getValue(Const_GoModule) ?: ""
        private val envOverride = mapOf<String, (Project) -> String>(
            Const_Path to { p -> combinePath(p, null, systemPath).joinToString(File.pathSeparator) },
            Const_GoPath to { p -> combineGoPath(p, null).joinToString(File.pathSeparator) },
            Const_GoModule to { p -> combineModuleOn(p, systemModuleOn) },
        )
    }

    // pure virtual functions ==========================================================================================
    // golangci-lint pack name for running OS
    abstract fun getPlatformSpecificBinName(meta: GithubRelease): String
    // host OS temp path
    protected abstract fun tempPath(): String
    // decompress golangci-lint pack on host OS
    protected abstract fun decompress(compressed: String, targetFile: String, to: String, setFraction: (Double) -> Unit, cancelled: () -> Boolean)
    // build debug command for running OS
    abstract fun buildCommand(params: List<String>, runningDir: String?, vars: List<String>): String
    // linter name on running OS with extension
    abstract fun linterName(): String
    // default path to put golangci-lint in host OS format
    abstract fun defaultPath(): String

    // virtual functions with default impl ==================================
    open fun pathSeparator(): String = File.pathSeparator
    // convert *relative* path in host OS format to running OS format
    open fun convertToPlatformPath(path: String): String = path
    // get all PATHs in running OS
    open fun getPathList(): List<String> = getEnvMap(listOf(Const_Path))[Const_Path]!!.split(pathSeparator())
    // check if this host OS path is executable on running OS
    open fun canExecute(path: String): Boolean = File(path).canExecute()
    // check if this host OS path is writeable on running OS
    open fun canWrite(path: String): Boolean = File(path).canWrite()
    // convert host OS path to running OS path
    open fun toRunningOSPath(path: String): String = path
    // get env values for given vars on running OS
    open fun getEnvMap(vars: List<String>): Map<String, String> =
        vars.associateWith { envOverride[it]?.invoke(project) ?: System.getenv(it) ?: "" }
    // run process on running OS. PATH in params or runningDir must be in running OS format
    open fun runProcess(params: List<String>, runningDir: String?, vars: List<String>, encoding: Charset = Charset.defaultCharset()): RunProcessResult =
        fetchProcessOutput(
            ProcessBuilder(params).apply {
                if (runningDir != null)
                    this.directory(File(runningDir))
                val curEnv = this.environment()
                getEnvMap(vars).forEach { kv -> curEnv[kv.key] = kv.value }
            }.start(),
            encoding
        )
    // fetch golangci-lint release for running OS (might pipe thru host OS)
    open fun fetchLatestGoLinter(destDir: String, setText: (String) -> Unit, setFraction: (Double) -> Unit, cancelled: () -> Boolean): String {
        HttpClientBuilder.create().disableContentCompression().build().use { httpClient ->
            setText("Getting latest release meta")
            val latest = getLatestReleaseMeta(httpClient)
            setFraction(0.2)

            if (cancelled())
                return ""

            // "golangci-lint-1.23.3-darwin-amd64.tar.gz"
            val binaryFileName = getPlatformSpecificBinName(latest)
            val asset = latest.assets.single { it.name == binaryFileName }
            // "/tmp/golangci-lint-1.23.3-darwin-amd64.tar.gz"
            val tmp = Paths.get(tempPath(), binaryFileName).toString()

            setText("Downloading $binaryFileName")
            httpClient.execute(HttpGet(asset.browserDownloadUrl)).use { response ->
                copy(response.entity.content, tmp, asset.size.toLong(), { setFraction(0.2 + 0.6 * it) }, cancelled)
            }

            val toFile = Paths.get(destDir, linterName()).toString()
            setText("Decompressing to $toFile")
            decompress(tmp, linterName(), toFile, { setFraction(0.8 + 0.2 * it) }, cancelled)
            File(tmp).delete()

            if (File(toFile).let { !it.canExecute() && !it.setExecutable(true) }) {
                throw Exception("Permission denied to execute $toFile")
            }
            return toFile
        }
    }
    open val defaultExecutable: String by lazy {
        getPathList()
            .map { Paths.get(it, linterName()).toString() }
            .firstOrNull { canExecute(it) } ?: ""
    }
    open fun adjustLinterExeChooser(initial: FileChooserDescriptor): FileChooserDescriptor =
        initial.also { it.withFileFilter { vf -> this.canExecute(vf.path) } }
}
