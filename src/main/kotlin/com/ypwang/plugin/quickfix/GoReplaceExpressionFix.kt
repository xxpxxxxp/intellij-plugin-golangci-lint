package com.ypwang.plugin.quickfix

import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

class GoReplaceExpressionFix(
        private val replacement: String,
        private val element: PsiElement
) : LocalQuickFix {
    override fun getFamilyName(): String = "Replace with '$replacement'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        element.replace(GoElementFactory.createExpression(project, replacement))
    }
}