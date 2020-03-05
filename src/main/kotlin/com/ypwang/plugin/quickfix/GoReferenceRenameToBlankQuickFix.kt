package com.ypwang.plugin.quickfix

import com.goide.psi.GoReferenceExpression
import com.goide.quickfix.GoRenameToBlankQuickFix
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project

class GoReferenceRenameToBlankQuickFix(private val element: GoReferenceExpression): LocalQuickFix {
    override fun getFamilyName(): String = GoRenameToBlankQuickFix.NAME

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        element.reference.handleElementRename("_")
    }
}