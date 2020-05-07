package com.ypwang.plugin.quickfix

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import java.awt.Desktop
import java.net.URL

class GoBringToExplanationFix(private val url: String): LocalQuickFix {
    override fun getFamilyName(): String = "What is it?"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        Desktop.getDesktop().browse(URL(url).toURI())
    }
}