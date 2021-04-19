package com.ypwang.plugin.quickfix

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoDeleteElementsFix(private val elements: List<PsiElement>, private val _text: String)
    : IntentionAction {
    override fun getFamilyName(): String = text
    override fun getText(): String = _text
    override fun startInWriteAction(): Boolean = true
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        elements.forEach { it.delete() }
    }
}