package com.ypwang.plugin.quickfix

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import java.awt.Desktop
import java.net.URL

class GoScopeLintFakeFix: LocalQuickFix {
    override fun getFamilyName(): String = "What is it?"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        Desktop.getDesktop().browse(URL("https://github.com/xxpxxxxp/intellij-plugin-golangci-lint/blob/master/explanation/scopelint.md").toURI())
    }
}