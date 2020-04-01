package com.ypwang.plugin.quickfix

import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment

class NoLintFuncCommentFix(
        private val linter: String,
        private val func: GoFunctionOrMethodDeclaration
): LocalQuickFix {
    override fun getFamilyName(): String = "Suppress linter '$linter'" + (func.identifier?.let { " for func '${it.text}'" } ?: "")

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val comment = GoElementFactory.createElement(project, "package a; \n //nolint:$linter\n}", PsiComment::class.java)
        if (comment != null) {
            func.parent.addBefore(comment, func)
            func.parent.addBefore(GoElementFactory.createNewLine(project), func)
        }
    }
}