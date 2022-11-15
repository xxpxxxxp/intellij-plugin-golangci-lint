package com.ypwang.plugin.platform

import com.intellij.execution.wsl.WslPath
import com.ypwang.plugin.model.RunProcessResult
import java.nio.charset.Charset

class WSL(goRoot: String): Linux() {
    private val distribution = WslPath.getDistributionByWindowsUncPath(goRoot)!!

    override fun runProcess(params: List<String>, runningDir: String?, env: Map<String, String>, encoding: Charset): RunProcessResult {
        return super.runProcess(params, runningDir, env, encoding)
    }

    override fun buildCommand(params: List<String>, runningDir: String?, env: Map<String, String>): String {
        TODO("Not yet implemented")
    }

    // expecting WSL UNC path
    override fun canExecute(path: String): Boolean = true
    override fun canWrite(path: String): Boolean {
        val path = distribution.getWslPath(path)
        return true
    }
}
