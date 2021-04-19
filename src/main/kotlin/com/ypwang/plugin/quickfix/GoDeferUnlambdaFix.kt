package com.ypwang.plugin.quickfix

import com.goide.psi.GoCallExpr
import com.goide.psi.GoDeferStatement
import com.goide.psi.GoFunctionLit
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoDeferUnlambdaFix(element: GoDeferStatement)
    : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = text
    override fun getText(): String = "Unlambda defer"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val call = (startElement as GoDeferStatement).expression as GoCallExpr
        val func = call.expression as GoFunctionLit
        val block = func.block
        if (block != null && block.statementList.size == 1) {
            call.replace(block.statementList.single())
        }
    }
}