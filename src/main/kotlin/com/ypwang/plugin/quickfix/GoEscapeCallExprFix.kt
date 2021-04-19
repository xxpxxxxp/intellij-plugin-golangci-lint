package com.ypwang.plugin.quickfix

import com.goide.psi.GoCallExpr
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoEscapeCallExprFix(element: GoCallExpr)
    : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    private val _text: String = element.expression.text
    override fun getFamilyName(): String = text
    override fun getText(): String = "Unwrap '$_text'"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val element = startElement as GoCallExpr
        element.replace(element.argumentList.expressionList.first())
    }
}