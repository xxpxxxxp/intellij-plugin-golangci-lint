package com.ypwang.plugin.quickfix

import com.goide.psi.GoIfStatement
import com.goide.psi.GoSimpleStatement
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

class GoShortenIfFix(element: GoSimpleStatement, private val ifStatementLine: Int) : LocalQuickFixOnPsiElement(element) {
    override fun getFamilyName(): String = text
    override fun getText(): String = "Shorten 'if' with assignment"

    override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val ifStatement = PsiTreeUtil.findFirstParent(file.findElementAt(document.getLineEndOffset(ifStatementLine))) { it is GoIfStatement } as GoIfStatement? ?: return
        ifStatement.replace(GoElementFactory.createIfStatement(
            project,
            "${startElement.text}; ${ifStatement.statement?.text}",
            ifStatement.block!!.statementList.joinToString("\n") { it.text },
            null
        ))
        startElement.delete()
    }
}