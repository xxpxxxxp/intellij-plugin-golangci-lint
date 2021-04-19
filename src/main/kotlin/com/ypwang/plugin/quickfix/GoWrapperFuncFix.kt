package com.ypwang.plugin.quickfix

import com.goide.psi.GoCallExpr
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoWrapperFuncFix(element: GoCallExpr)
    : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = text
    override fun getText(): String = "Replace with '${(myStartElement.element as GoCallExpr).expression.text}All'"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val element = startElement as GoCallExpr

        val expressionBuilder = StringBuilder()
        expressionBuilder.append("${element.expression.text}All(")
        expressionBuilder.append(element.argumentList.expressionList.dropLast(1).joinToString(", ") { it.text })
        expressionBuilder.append(")")

        element.replace(GoElementFactory.createCallExpression(project, expressionBuilder.toString()))
    }
}