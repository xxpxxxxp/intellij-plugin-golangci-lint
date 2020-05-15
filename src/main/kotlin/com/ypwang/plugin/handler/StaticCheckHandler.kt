package com.ypwang.plugin.handler

import com.goide.psi.GoReferenceExpression
import com.goide.psi.GoStatement
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.GoDeleteElementsFix
import com.ypwang.plugin.quickfix.GoReferenceRenameToBlankQuickFix

object StaticCheckHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            when (issue.Text.substring(0, issue.Text.indexOf(':'))) {
                // empty branch
                "SA9003" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoStatement ->
                        arrayOf<LocalQuickFix>(GoDeleteElementsFix(listOf(element), "Remove branch")) to element.textRange
                    }
                // this value of `xxx` is never used
                "SA4006" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoReferenceExpression ->
                        // get the variable
                        val begin = issue.Text.indexOf('`')
                        val end = issue.Text.indexOf('`', begin + 1)
                        val variable = issue.Text.substring(begin + 1, end)
                        if (element.text == variable)
                            arrayOf<LocalQuickFix>(GoReferenceRenameToBlankQuickFix(element)) to element.identifier.textRange
                        else NonAvailableFix
                    }
                else -> NonAvailableFix
            }
}