package com.ypwang.plugin.platform

import com.intellij.openapi.project.Project
import com.ypwang.plugin.model.GithubRelease
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.zip.GZIPInputStream

abstract class UnixLikePlatform(project: Project) : Platform(project) {
    override fun tempPath(): String = "/tmp"
    // decompress .tar.gz
    override fun decompress(compressed: String, targetFile: String, to: String, setFraction: (Double) -> Unit, cancelled: () -> Boolean) {
        TarArchiveInputStream(GZIPInputStream(FileInputStream(compressed))).use { tis ->
            var tarEntry = tis.nextTarEntry
            while (tarEntry != null) {
                if (cancelled()) return
                if (tarEntry.name.endsWith(targetFile)) {
                    copy(tis, to, tarEntry.size, setFraction, cancelled)
                    return
                }
                tarEntry = tis.nextTarEntry
            }
        }
        throw FileNotFoundException(targetFile)
    }
    // quote path/variable/param to avoid break by space
    override fun buildCommand(params: List<String>, runningDir: String?, vars: List<String>): String =
        StringBuilder().apply {
            // open into working dir
            if (runningDir != null)
                this.append("cd '$runningDir' && ")
            // set env
            getEnvMap(vars).forEach { (k, v) -> this.append("export $k='$v' && ") }
            // quote all params
            this.append(params.joinToString(" ") { "'$it'" })
        }.toString()
    override fun linterName(): String = LinterName
    override fun defaultPath(): String = "/usr/local/bin"
}

open class Linux(project: Project) : UnixLikePlatform(project) {
    override fun getPlatformSpecificBinName(meta: GithubRelease): String = "${LinterName}-${meta.name.substring(1)}-linux-${arch()}.tar.gz"
}

class Mac(project: Project) : UnixLikePlatform(project) {
    override fun getPlatformSpecificBinName(meta: GithubRelease): String = "${LinterName}-${meta.name.substring(1)}-darwin-${arch()}.tar.gz"
}
