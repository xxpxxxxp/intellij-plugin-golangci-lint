package com.ypwang.plugin.quickfix

import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class NoLintFuncCommentFix(
        private val linter: String,
        private val funcName: String,
        func: GoFunctionOrMethodDeclaration
): LocalQuickFixOnPsiElement(func) {
    override fun getFamilyName(): String = text

    override fun getText(): String = "Suppress linter '$linter' for func '$funcName'"

    override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
        startElement.parent.addBefore(GoElementFactory.createComment(project, "//nolint:$linter"), startElement)
        startElement.parent.addBefore(GoElementFactory.createNewLine(project), startElement)
    }
}