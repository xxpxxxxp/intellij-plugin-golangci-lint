package com.ypwang.plugin.quickfix

import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.psi.GoParameterDeclaration
import com.goide.psi.GoParameters
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoShortenParameterFix(element: GoFunctionOrMethodDeclaration)
    : LocalQuickFixAndIntentionActionOnPsiElement(element)  {
    override fun getFamilyName(): String = text
    override fun getText(): String = "Shorten parameters"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val signature = (startElement as GoFunctionOrMethodDeclaration).signature!!
        signature.replace(GoElementFactory.createFunctionSignatureFromText(
            project,
            shorten(signature.parameters),
            signature.result?.type?.text ?: signature.result?.parameters?.let { shorten(it) } ?: "",
            null,
            true
        ))
    }

    private fun GoParameterDeclaration.fullType(): String =
        if (this.isVariadic) "...${this.type!!.text}"
        else this.type?.text ?: ""

    private fun shorten(params: GoParameters): String {
        val sb = StringBuilder()
        val previous = mutableListOf<GoParameterDeclaration>()

        for (param in params.parameterDeclarationList) {
            if (previous.isNotEmpty() && previous.last().type != null && previous.last().fullType() != param.fullType()) {
                val type = previous.last().type!!
                sb.append(previous.flatMap { it.paramDefinitionList }.joinToString(", "){ it.identifier.text })
                sb.append(" ")
                sb.append(type.text)
                sb.append(", ")
                previous.clear()
            }

            previous.add(param)
        }

        sb.append(previous.flatMap { it.paramDefinitionList }.joinToString(", "){ it.identifier.text })
        sb.append(" ")
        sb.append(previous.last().type!!.text)

        return sb.toString()
    }
}