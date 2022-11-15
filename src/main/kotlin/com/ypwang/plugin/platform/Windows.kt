package com.ypwang.plugin.platform

import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.zip.ZipInputStream

class Windows : Platform() {
    override fun os(): String = "windows"
    override fun suffix(): String = "zip"
    override fun tempPath(): String = System.getenv("TEMP")
    override fun decompress(compressed: String, targetFile: String, to: String, setFraction: (Double) -> Unit, cancelled: () -> Boolean) {
        ZipInputStream(FileInputStream(compressed)).use { zis ->
            var zipEntry = zis.nextEntry
            while (zipEntry != null) {
                if (cancelled()) return
                if (zipEntry.name.endsWith(targetFile)) {
                    // sadly zip size is always -1 (unknown), based on experience we assume it's 21MB
                    copy(zis, to, 22020096, setFraction, cancelled)
                    zis.closeEntry()
                    return
                }
                zipEntry = zis.nextEntry
            }
            zis.closeEntry()
        }
        throw FileNotFoundException(targetFile)
    }
    override fun buildCommand(params: List<String>, runningDir: String?, env: Map<String, String>): String =
        StringBuilder().apply {
            runningDir?.let {
                this.append("cd $it && ")    // open into working dir
            }
            for ((k, v) in env)
                this.append("set \"$k=$v\" && ")
            this.append(params.joinToString(" ") { "\"$it\"" })
        }.toString()
    override fun linterName(): String = "$LinterName.exe"
    override fun defaultPath(): String = System.getenv("PUBLIC")
}