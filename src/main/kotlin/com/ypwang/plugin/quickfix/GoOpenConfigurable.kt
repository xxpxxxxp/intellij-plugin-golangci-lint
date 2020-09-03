package com.ypwang.plugin.quickfix

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

class GoOpenConfigurable(private val _text: String, private val configurable: (Project) -> Configurable): LocalQuickFix {
    override fun getFamilyName(): String = _text

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        ShowSettingsUtil.getInstance().editConfigurable(project, configurable.invoke(project))
    }
}