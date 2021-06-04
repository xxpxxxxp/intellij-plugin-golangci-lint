package com.ypwang.plugin.quickfix

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import java.awt.Desktop
import java.net.URL

class GoBringToExplanationFix(private val url: String, private val _text: String = "What is it?")
    : IntentionAction {
    override fun getFamilyName(): String = text
    override fun getText(): String = _text
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true
    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        Desktop.getDesktop().browse(URL(url).toURI())
    }
}