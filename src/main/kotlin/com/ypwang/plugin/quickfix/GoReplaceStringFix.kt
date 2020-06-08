package com.ypwang.plugin.quickfix

import com.goide.psi.GoStringLiteral
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoReplaceStringFix(private val _text: String, element: GoStringLiteral, private val transform: (String) -> String): LocalQuickFixOnPsiElement(element) {
    override fun getFamilyName(): String = _text

    override fun getText(): String = _text

    override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
        val stringLiteral = startElement as GoStringLiteral
        stringLiteral.replace(GoElementFactory.createStringLiteral(project, transform(stringLiteral.decodedText)))
    }
}