package com.ypwang.plugin.quickfix

import com.goide.psi.GoSelectStatement
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoSimplifySimpleChanSelectFix(element: GoSelectStatement): LocalQuickFixOnPsiElement(element) {
    override fun getFamilyName(): String = text

    override fun getText(): String = "Rewrite to 'if'"

    override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
        val element = startElement as GoSelectStatement
        val caseClause = element.commClauseList.single()
        val statement = caseClause.commCase!!.recvStatement!!

        element.replace(GoElementFactory.createIfStatement(
                project,
                "${statement.text}; true",
                caseClause.statementList.joinToString("\n") { it.text },
                null
        ))
    }
}