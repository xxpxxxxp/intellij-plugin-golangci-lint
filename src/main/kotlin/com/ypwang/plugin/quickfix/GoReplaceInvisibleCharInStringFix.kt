package com.ypwang.plugin.quickfix

import com.goide.psi.GoStringLiteral
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoReplaceInvisibleCharInStringFix(element: GoStringLiteral, private val hex: Int): LocalQuickFixOnPsiElement(element) {
    override fun getFamilyName() = text

    override fun getText() = "Escape string"

    override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
        val sb = StringBuilder()
        for (c in startElement.text) {
            if (c.toInt() == hex) sb.append("\\u${c.toInt().toString(16)}")
            else sb.append(c)
        }
        startElement.replace(GoElementFactory.createStringLiteral(project, sb.toString()))
    }
}