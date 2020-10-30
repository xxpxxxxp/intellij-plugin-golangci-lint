package com.ypwang.plugin.quickfix

import com.goide.psi.GoReferenceExpression
import com.goide.quickfix.GoRenameToBlankQuickFix
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoReferenceRenameToBlankQuickFix(element: GoReferenceExpression): LocalQuickFixOnPsiElement(element) {
    override fun getFamilyName(): String = text

    override fun getText(): String = GoRenameToBlankQuickFix.getQuickFixName()

    override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
        (startElement as GoReferenceExpression).reference.handleElementRename("_")
    }
}