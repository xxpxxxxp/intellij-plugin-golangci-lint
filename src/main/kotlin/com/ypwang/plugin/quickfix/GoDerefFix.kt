package com.ypwang.plugin.quickfix

import com.goide.psi.GoParenthesesExpr
import com.goide.psi.GoUnaryExpr
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoDerefFix(element: GoParenthesesExpr)
    : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = text
    override fun getText(): String = "Dereference '${myStartElement.element?.text}'"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val element = startElement as GoParenthesesExpr
        val inner = element.expression as GoUnaryExpr
        val deref = inner.expression as PsiElement
        if (deref is GoUnaryExpr) inner.replace(deref)
        else element.replace(deref)
    }
}