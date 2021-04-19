package com.ypwang.plugin.handler

import com.goide.psi.GoLiteral
import com.goide.psi.GoParamDefinition
import com.goide.psi.GoReferenceExpression
import com.goide.psi.GoStatement
import com.goide.quickfix.GoRenameToBlankQuickFix
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.GoDeleteElementFix
import com.ypwang.plugin.quickfix.GoReferenceRenameToBlankQuickFix
import com.ypwang.plugin.quickfix.GoReplaceElementFix

object StaticCheckHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<IntentionAction>, TextRange?> =
            when (issue.Text.substring(0, issue.Text.indexOf(':'))) {
                // this value of `xxx` is never used
                "SA4006" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoReferenceExpression ->
                        // get the variable
                        val begin = issue.Text.indexOf('`')
                        val end = issue.Text.indexOf('`', begin + 1)
                        val variable = issue.Text.substring(begin + 1, end)
                        if (element.text == variable)
                            arrayOf<IntentionAction>(GoReferenceRenameToBlankQuickFix(element)) to element.identifier.textRange
                        else NonAvailableFix
                    }
                // argument xxx is overwritten before first use
                "SA4009" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoParamDefinition ->
                        arrayOf<IntentionAction>(toIntentionAction(GoRenameToBlankQuickFix(element))) to element.identifier.textRange
                    }
                // file mode '777' evaluates to 01411; did you mean '0777'?"
                "SA9002" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoLiteral ->
                        val (currentAssignment, replace) = extractQuote(issue.Text, 2)
                        if (element.int?.text == currentAssignment)
                            arrayOf<IntentionAction>(GoReplaceElementFix(replace, element, GoLiteral::class.java)) to element.textRange
                        else NonAvailableFix
                    }
                // empty branch
                "SA9003" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoStatement ->
                        arrayOf<IntentionAction>(GoDeleteElementFix(element, "Remove branch")) to element.textRange
                    }
                else -> NonAvailableFix
            }
}