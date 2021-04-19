package com.ypwang.plugin.quickfix

import com.goide.psi.GoParenthesesExpr
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoDeParentheseFix(element: GoParenthesesExpr)
    : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = text
    override fun getText(): String = "De-parentheses"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        startElement.replace((startElement as GoParenthesesExpr).expression!!)
    }
}