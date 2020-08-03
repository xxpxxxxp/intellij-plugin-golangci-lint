package com.ypwang.plugin.quickfix

import com.goide.psi.GoExprSwitchStatement
import com.goide.psi.GoIfStatement
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoIfToSwitchFix(element: GoIfStatement): LocalQuickFixOnPsiElement(element) {
    override fun getFamilyName(): String = text

    override fun getText(): String = "Rewrite to 'switch'"

    override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
        val element = startElement as GoIfStatement

        var ifStatement = element
        val lines = mutableListOf<String>()

        while (true) {
            lines.add("case ${ifStatement.condition!!.text}:")
            ifStatement.block?.let { lines.addAll(it.statementList.map { s -> s.text }) }
            if (ifStatement.elseStatement?.ifStatement != null)
                ifStatement = ifStatement.elseStatement!!.ifStatement!!
            else {
                if (ifStatement.elseStatement != null) {
                    lines.add("default:")
                    ifStatement.elseStatement!!.block?.let { lines.addAll(it.statementList.map { s -> s.text }) }
                }
                break
            }
        }

        val switchStatement = GoElementFactory.createElement(project, "package a; func a() { switch {\n ${lines.joinToString("\n")} \n} }", GoExprSwitchStatement::class.java)
        if (switchStatement != null)
            element.replace(switchStatement)
    }
}