package com.ypwang.plugin.quickfix

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

class GoDeleteElementsFix(private val elements: List<PsiElement>): LocalQuickFix {
    override fun getFamilyName(): String = "Remove whitespace"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        elements.forEach { it.delete() }
    }
}