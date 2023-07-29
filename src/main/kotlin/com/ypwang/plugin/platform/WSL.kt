package com.ypwang.plugin.platform

import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.project.Project
import com.ypwang.plugin.Const_GoModule
import com.ypwang.plugin.Const_GoPath
import com.ypwang.plugin.Const_Path
import com.ypwang.plugin.model.RunProcessResult
import java.io.File
import java.nio.charset.Charset

// host OS:    Windows
// running OS: Linux
class WSL(project: Project, private val distribution: WSLDistribution): Linux(project) {
    companion object {
        private const val pathSeparator = ":"

        private val envOverride = mapOf<String, (Project, String, (String) -> String) -> String>(
            Const_Path to { p, path, converter -> combinePath(p, converter, path).joinToString(pathSeparator) },
            Const_GoPath to { p, _, converter -> combineGoPath(p, converter).joinToString(pathSeparator) },
            Const_GoModule to { p, path, _ -> combineModuleOn(p, path) }
        )
    }

    override fun tempPath(): String = System.getenv("TEMP")
    // WSL util will log the executed command
    override fun buildCommand(params: List<String>, runningDir: String?, vars: List<String>): String =
        "Please search above log with 'command on wsl: ', Or match with regex: '(?<=command on wsl: ).*(?= was failed)' in supported editor like VSCode/Sublime/IDE"
    override fun defaultPath(): String = distribution.getWindowsPath(super.defaultPath())
    override fun pathSeparator(): String = pathSeparator
    // Windows relative path to Linux
    override fun convertToPlatformPath(path: String): String = path.replace('\\', '/')
    // not include mounted paths, and convert to host OS path
    override fun getPathList(): List<String> = super.getPathList().filter { !it.startsWith(distribution.mntRoot) }.map(distribution::getWindowsPath)
    // check whether this host OS path is available in current WSL distribution
    private fun isWSLAvailablePath(path: String): Boolean = WslPath.getDistributionByWindowsUncPath(path)?.equals(distribution) ?: true
    // cannot easily check executable and writeable, just check if available
    override fun canExecute(path: String): Boolean = isWSLAvailablePath(path) && File(path).isFile
    override fun canWrite(path: String): Boolean = isWSLAvailablePath(path)
    override fun toRunningOSPath(path: String): String = distribution.getWslPath(path)!!
    // get env variables inside WSL
    override fun getEnvMap(vars: List<String>): Map<String, String> {
        val wslVars = distribution.environment ?: mapOf()
        return vars.associateWith { v ->
            val current = wslVars.getOrDefault(v, "")
            envOverride[v]?.invoke(project, current) { distribution.getWslPath(it)!! } ?: current
        }
    }
    override fun runProcess(params: List<String>, runningDir: String?, vars: List<String>, encoding: Charset): RunProcessResult =
        distribution.executeOnWsl(
            params,
            WSLCommandLineOptions().apply {
                if (runningDir != null)
                    this.remoteWorkingDirectory = runningDir
                getEnvMap(vars).forEach { (k, v) ->  this.addInitCommand("export $k='$v'") }
            },
            120000,     // a reasonable timeout
            null
        ).let { RunProcessResult(it.exitCode, it.stdout, it.stderr) }
    override fun fetchLatestGoLinter(destDir: String, setText: (String) -> Unit, setFraction: (Double) -> Unit, cancelled: () -> Boolean): String {
        val d = WslPath.getDistributionByWindowsUncPath(destDir)
            ?: return super.fetchLatestGoLinter(destDir, setText, setFraction, cancelled)       // destDir is windows path

        if (d != distribution)
            throw Exception("cannot download to a different WSL distribution ${d.msId} other than ${distribution.msId}")

        setText("Copy into WSL")
        // destDir is WSL UNC path, first download & unpack to windows temp dir, then copy into WSL
        val tempDir = System.getenv("TEMP")
        val binary = super.fetchLatestGoLinter(tempDir, setText, { setFraction(it * 0.9) }, cancelled)
        val wslPath = "${distribution.getWslPath(destDir)!!}/${linterName()}"
        val cp = distribution.executeOnWsl(
            listOf("cp", distribution.getWslPath(binary), wslPath),
            WSLCommandLineOptions(),
            2000,
            null
        )

        if (cp.exitCode != 0)
            throw Exception("Failed to copy $binary into WSL $wslPath: ${cp.stderr}")

        return distribution.getWindowsPath(wslPath)
    }
}
