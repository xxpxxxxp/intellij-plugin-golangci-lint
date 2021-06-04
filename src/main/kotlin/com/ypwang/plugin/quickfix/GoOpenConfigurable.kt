package com.ypwang.plugin.quickfix

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

class GoOpenConfigurable(private val _text: String, private val configurable: (Project) -> Configurable)
    : IntentionAction {
    override fun getFamilyName(): String = text
    override fun getText(): String = _text
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true
    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        ShowSettingsUtil.getInstance().editConfigurable(project, configurable.invoke(project))
    }
}