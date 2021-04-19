package com.ypwang.plugin.quickfix

import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoSimplifyIfReturnFix(start: PsiElement, end: PsiElement, private val replace: String)
    : LocalQuickFixAndIntentionActionOnPsiElement(start, end) {
    override fun getFamilyName(): String = text
    override fun getText(): String = "Replace with '$replace'"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        // end is return statement
        endElement.replace(GoElementFactory.createReturnStatement(project, replace.removePrefix("return ")))
        // start is if statement
        startElement.delete()
    }
}