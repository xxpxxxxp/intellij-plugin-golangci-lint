package com.ypwang.plugin.quickfix

import com.goide.psi.GoConditionalExpr
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoReplaceWithErrorsIsFix(element: GoConditionalExpr)
    : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName() = text
    override fun getText(): String = "Rewrite to errors.Is"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val element = startElement as GoConditionalExpr
        var expression = "errors.Is(${element.left.text}, ${element.right?.text})"
        if (element.notEq != null) {
            expression = "!$expression"
        }

        element.replace(GoElementFactory.createExpression(project, expression))
    }
}