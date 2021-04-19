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

class GoReorderFuncReturnFix(element: GoFunctionOrMethodDeclaration)
    : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = text
    override fun getText(): String = "Reorder result parameters"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val method = startElement as GoFunctionOrMethodDeclaration
        val signature = Objects.requireNonNull(method.signature) as GoSignature
        val resultParameters = GoChangeSignatureBuilder.getResultParameters(signature)

        val errorParam = resultParameters.firstOrNull { it.type?.text == "error" }
        if (errorParam != null) {
            resultParameters.remove(errorParam)
            resultParameters.add(errorParam)
            ApplicationManager.getApplication().invokeLater {
                GoChangeSignatureBuilder.create(method).withResultParameters(resultParameters).refactorImplementations(true).run()
            }
        }
    }
}