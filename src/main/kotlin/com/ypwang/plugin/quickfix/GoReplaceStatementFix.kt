package com.ypwang.plugin.quickfix

import com.goide.psi.GoStatement
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoReplaceStatementFix(
        private val replacement: String,
        val element: GoStatement
) : LocalQuickFixOnPsiElement(element) {
    override fun getFamilyName(): String = text
    override fun getText(): String = "Replace with '$replacement'"

    override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
        val element = startElement as GoStatement
        element.replace(GoElementFactory.createStatement(project, replacement))
    }
}