package com.ypwang.plugin.quickfix

import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.psi.GoSignature
import com.goide.refactor.changeSignature.GoChangeSignatureBuilder
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.util.*

class GoReorderFuncParamFix(element: GoFunctionOrMethodDeclaration): LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = text

    override fun getText(): String = "Reorder parameters"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val method = startElement as GoFunctionOrMethodDeclaration
        val signature = Objects.requireNonNull(method.signature) as GoSignature
        val parameters = GoChangeSignatureBuilder.getParameters(signature)

        val contextParam = parameters.firstOrNull { it.type?.text == "context.Context" }
        if (contextParam != null) {
            parameters.remove(contextParam)
            parameters.add(0, contextParam)
            ApplicationManager.getApplication().invokeLater {
                GoChangeSignatureBuilder.create(method).withParameters(parameters).refactorImplementations(true).run()
            }
        }
    }
}