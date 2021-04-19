package com.ypwang.plugin.quickfix

import com.goide.psi.GoReferenceExpression
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoReferenceRenameToBlankQuickFix(element: GoReferenceExpression)
    : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = text
    override fun getText(): String = "Rename to '_'"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        (startElement as GoReferenceExpression).reference.handleElementRename("_")
    }
}