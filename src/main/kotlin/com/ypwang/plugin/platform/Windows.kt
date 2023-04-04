package com.ypwang.plugin.platform

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.ypwang.plugin.model.GithubRelease
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.zip.ZipInputStream

class Windows(project: Project) : Platform(project) {
    override fun getPlatformSpecificBinName(meta: GithubRelease): String = "${LinterName}-${meta.name.substring(1)}-windows-${arch()}.zip"
    override fun tempPath(): String = System.getenv("TEMP")
    // decompress .zip
    override fun decompress(compressed: String, targetFile: String, to: String, setFraction: (Double) -> Unit, cancelled: () -> Boolean) {
        ZipInputStream(FileInputStream(compressed)).use { zis ->
            var zipEntry = zis.nextEntry
            while (zipEntry != null) {
                if (cancelled()) return
                if (zipEntry.name.endsWith(targetFile)) {
                    // sadly zip size is always -1 (unknown), based on experience we assume it's 21MB
                    copy(zis, to, 25000000, setFraction, cancelled)
                    zis.closeEntry()
                    return
                }
                zipEntry = zis.nextEntry
            }
            zis.closeEntry()
        }
        throw FileNotFoundException(targetFile)
    }
    // quote path/variable/param to avoid break by space
    override fun buildCommand(params: List<String>, runningDir: String?, vars: List<String>): String =
        StringBuilder().apply {
            // open into working dir
            if (runningDir != null)
                this.append("cd \"$runningDir\" && ")
            // set env
            getEnvMap(vars).forEach { (k, v) -> this.append("set $k=\"$v\" && ") }
            // quote [1, n) params
            this.append(params.withIndex().joinToString(" ") { if (it.index == 0) it.value else "\"${it.value}\"" })
        }.toString()
    override fun linterName(): String = "$LinterName.exe"
    override fun defaultPath(): String = System.getenv("PUBLIC")
    override fun adjustLinterExeChooser(initial: FileChooserDescriptor): FileChooserDescriptor =
        initial.also { it.withFileFilter { vf -> "exe".equals(vf.extension, ignoreCase = true) } }
}