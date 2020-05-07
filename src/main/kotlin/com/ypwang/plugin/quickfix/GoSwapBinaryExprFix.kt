package com.ypwang.plugin.quickfix

import com.goide.psi.GoBinaryExpr
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoSwapBinaryExprFix(element: GoBinaryExpr): LocalQuickFixOnPsiElement(element) {
    override fun getFamilyName() = text

    override fun getText(): String = "Swap operands"

    override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
        val element = startElement as GoBinaryExpr
        val left = element.left.copy()
        element.left.replace(element.right!!)
        element.right!!.replace(left)
    }
}