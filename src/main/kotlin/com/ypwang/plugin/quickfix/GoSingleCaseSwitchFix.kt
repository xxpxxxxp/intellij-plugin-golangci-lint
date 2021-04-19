package com.ypwang.plugin.quickfix

import com.goide.psi.GoExprSwitchStatement
import com.goide.psi.GoSwitchStatement
import com.goide.psi.GoTypeSwitchStatement
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoSingleCaseSwitchFix(element: GoSwitchStatement)
    : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = text
    override fun getText(): String = "Rewrite to 'if'"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        when (val element = startElement as GoSwitchStatement) {
            is GoTypeSwitchStatement -> {
                val caseClause = element.typeCaseClauseList.single()
                val cond = element.typeSwitchGuard

                val varName = cond.varDefinition?.let { it.text }?: "_"
                assert(cond.expression.text.endsWith(".(type)"))

                element.replace(GoElementFactory.createIfStatement(
                        project,
                        "$varName, ok := ${cond.expression.text.dropLast(7)}.(${caseClause.type!!.text}); ok",
                        caseClause.statementList.joinToString("\n") { it.text },
                        null
                ))
            }
            is GoExprSwitchStatement -> {
                val caseClause = element.exprCaseClauseList.single()
                val cond = element.condition!!.text

                val condBuilder = StringBuilder()
                element.initStatement?.let {
                    condBuilder.append("${it.text}; ")
                }
                condBuilder.append(caseClause.expressionList.joinToString(" || "){ "$cond == ${it.text}" })

                element.replace(GoElementFactory.createIfStatement(
                        project,
                        condBuilder.toString(),
                        caseClause.statementList.joinToString("\n") { it.text },
                        null
                ))
            }
        }
    }
}