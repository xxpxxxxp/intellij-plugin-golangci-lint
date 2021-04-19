package com.ypwang.plugin.quickfix

import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoReplaceElementFix<T: PsiElement>(
        private val replacement: String,
        element: PsiElement,
        private val typeTag: Class<T>
) : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = text
    override fun getText(): String = "Replace with '$replacement'"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        startElement.replace(GoElementFactory.createElement(project, "package a; func a() {\n $replacement }", typeTag) ?: return)
    }
}