package com.ypwang.plugin.watchers

import com.goide.GoEnvironmentUtil
import com.goide.watchers.consumers.GoToolTaskConsumer
import com.intellij.ide.macro.FilePathMacro
import com.intellij.openapi.project.Project
import com.intellij.plugins.watcher.model.TaskOptions
import com.intellij.psi.PsiFile

class GoFumportsTaskConsumer : GoToolTaskConsumer() {
    companion object {
        private val EXECUTABLE_NAME = GoEnvironmentUtil.getBinaryFileNameForPath("gofumports")
    }

    override fun getOptionsTemplate(): TaskOptions {
        return createDefaultOptions().apply {
            this.name = "gofumports"
            this.description = "Runs `$EXECUTABLE_NAME` on current Go file directory"
            this.program = EXECUTABLE_NAME
            this.arguments = "-w $${FilePathMacro().name}$"
            this.output = "$${FilePathMacro().name}$"
        }
    }

    override fun additionalConfiguration(project: Project, file: PsiFile?, options: TaskOptions) {
        super.additionalConfiguration(project, file, options)
        installGoToolIfNeeded(project, file, EXECUTABLE_NAME, "mvdan.cc/gofumpt/gofumports")
    }
}