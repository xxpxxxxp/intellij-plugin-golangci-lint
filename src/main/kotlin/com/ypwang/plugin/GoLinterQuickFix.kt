package com.ypwang.plugin

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.ypwang.plugin.model.LintIssue

private val singleton = arrayOf<LocalQuickFix>()
val quickFixHandler = mapOf(
        "ineffassign" to ::ineffassignHandler,
        "structcheck" to ::structcheckHandler,
        "varcheck" to ::varcheckHandler,
        "deadcode" to ::deadcodeHandler,
        "unused" to ::unusedHandler,
        "gocritic" to ::gocriticHandler
)
val defaultHandler: (Project, PsiFile, LintIssue) -> Array<LocalQuickFix> = { _, _, _ -> singleton }

private fun ineffassignHandler(project: Project, file: PsiFile, issue: LintIssue): Array<LocalQuickFix> {
    return singleton
}

private fun structcheckHandler(project: Project, file: PsiFile, issue: LintIssue): Array<LocalQuickFix> {
    return singleton
}

private fun varcheckHandler(project: Project, file: PsiFile, issue: LintIssue): Array<LocalQuickFix> {
    return singleton
}

private fun deadcodeHandler(project: Project, file: PsiFile, issue: LintIssue): Array<LocalQuickFix> {
    return singleton
}

private fun unusedHandler(project: Project, file: PsiFile, issue: LintIssue): Array<LocalQuickFix> {
    return singleton
}

private fun gocriticHandler(project: Project, file: PsiFile, issue: LintIssue): Array<LocalQuickFix> {
    return singleton
}