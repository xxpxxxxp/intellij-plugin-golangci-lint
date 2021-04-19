package com.ypwang.plugin.quickfix

import com.goide.psi.*
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoDeleteConstDefinitionFix(element: GoConstDefinition) : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = text
    override fun getText(): String = "Delete constant '${(startElement as GoConstDefinition).name}'"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val element = startElement as GoConstDefinition
        if (element.isValid) {
            val parent = element.parent
            if (parent is GoConstSpec) {
                parent.deleteDefinition(element)
            }
        }
    }
}