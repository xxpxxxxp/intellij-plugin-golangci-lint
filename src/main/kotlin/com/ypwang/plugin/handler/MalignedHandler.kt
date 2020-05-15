package com.ypwang.plugin.handler

import com.goide.psi.GoTypeDeclaration
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.GoReorderStructFieldFix

object MalignedHandler : ProblemHandler() {
    override fun description(issue: LintIssue): String {
        val lineBreak = issue.Text.indexOf(":\n")
        if (lineBreak != -1)
            return issue.Text.substring(0, lineBreak) + " (maligned)"

        return super.description(issue)
    }

    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> {
        val lineBreak = issue.Text.indexOf(":\n")
        if (lineBreak != -1) {
            return chainFindAndHandle(file, document, issue, overrideLine) { element: GoTypeDeclaration ->
                arrayOf<LocalQuickFix>(
                        GoReorderStructFieldFix(
                                element.typeSpecList.first().identifier.text,
                                issue.Text.substring(lineBreak + 2).trim('`'),
                                element
                        )
                ) to element.typeSpecList.first().identifier.textRange
            }
        }

        return NonAvailableFix
    }
}