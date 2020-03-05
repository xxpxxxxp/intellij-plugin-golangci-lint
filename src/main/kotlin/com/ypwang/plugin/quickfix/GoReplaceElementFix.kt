package com.ypwang.plugin.quickfix

import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

class GoReplaceElementFix<T: PsiElement>(
        private val replacement: String,
        private val element: PsiElement,
        private val typeTag: Class<T>
) : LocalQuickFix {
    override fun getFamilyName(): String = "Replace with '$replacement'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val newElement = GoElementFactory.createElement(project, "package a; func a() {\n $replacement }", typeTag)
        if (newElement != null)
            element.replace(newElement)
    }
}