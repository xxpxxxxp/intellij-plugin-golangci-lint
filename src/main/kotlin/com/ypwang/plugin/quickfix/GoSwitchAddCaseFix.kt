package com.ypwang.plugin.quickfix

import com.goide.psi.GoExprCaseClause
import com.goide.psi.GoExprSwitchStatement
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoSwitchAddCaseFix(private val cases: String, element: GoExprSwitchStatement): LocalQuickFixOnPsiElement(element) {
    override fun getFamilyName(): String = text

    override fun getText(): String = "Add cases '$cases'"

    override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
        val statement = startElement as GoExprSwitchStatement
        statement.addBefore(GoElementFactory.createElement(
                project,
                "package main\nfunc _() { switch {\n case $cases:\n } }",
                GoExprCaseClause::class.java)!!, statement.rbrace)
    }
}