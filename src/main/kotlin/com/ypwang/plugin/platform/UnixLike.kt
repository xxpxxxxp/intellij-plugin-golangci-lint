package com.ypwang.plugin.platform

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.zip.GZIPInputStream

abstract class UnixLikePlatform: Platform() {
    override fun suffix(): String = "tar.gz"
    override fun tempPath(): String = "/tmp"
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
    override fun buildCommand(params: List<String>, runningDir: String?, env: Map<String, String>): String =
        StringBuilder().apply {
            runningDir?.let {
                this.append("cd $it && ")    // open into working dir
            }
            for ((k, v) in env)              // set env
                this.append("export $k=$v && ")
            this.append(params.joinToString(" "){ "'$it'" })
        }.toString()
    override fun linterName(): String = LinterName
    override fun defaultPath(): String = "/usr/local/bin"
}

open class Linux: UnixLikePlatform() {
    override fun os(): String = "linux"
}

class Mac: UnixLikePlatform() {
    override fun os(): String = "darwin"
}
