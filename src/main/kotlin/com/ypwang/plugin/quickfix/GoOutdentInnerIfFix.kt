package com.ypwang.plugin.quickfix

import com.goide.psi.GoElseStatement
import com.goide.psi.GoIfStatement
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoOutdentInnerIfFix(element: GoElseStatement): LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = text

    override fun getText(): String = "Outdent inner 'if' branch"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val elseStatement = startElement as GoElseStatement
        val innerStatement = elseStatement.block!!.statementList.single()

        if (innerStatement is GoIfStatement) {
            elseStatement.replace(GoElementFactory.createIfElseIfStatement(project,
                    "a"/*fake condition*/,
                    "a++"/*fake statement*/,
                    "else ${innerStatement.text}"
            ).elseStatement!!)
        }
    }
}