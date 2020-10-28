package com.ypwang.plugin.handler

import com.goide.psi.*
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.GoBringToExplanationFix
import com.ypwang.plugin.quickfix.GoErrorTypeAssertionFix
import com.ypwang.plugin.quickfix.GoReplaceWithErrorsIsFix
import com.ypwang.plugin.quickfix.GoWrapErrorFormatFix

object ErrorLintHandler : ProblemHandler() {
    private fun fixErrorfVerb(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> {
        val pos = calcPos(document, issue, overrideLine)
        var element = file.findElementAt(pos)
        while (true) {
            when (element) {
                is GoCallExpr ->
                    if (element.expression.text.startsWith("fmt.Errorf"))
                        return arrayOf<LocalQuickFix>(GoWrapErrorFormatFix(element, pos)) to element.argumentList.expressionList.first().textRange
                is GoFile, null -> return NonAvailableFix
            }

            element = element!!.parent
        }
    }

    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            when {
                issue.Text == "non-wrapping format verb for fmt.Errorf. Use `%w` to format errors" ->
                    fixErrorfVerb(file, document, issue, overrideLine)
                issue.Text == "type assertion on error will fail on wrapped errors. Use errors.As to check for specific errors" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoTypeAssertionExpr ->
                        val parent = element.parent
                        // must check ok, otherwise we are expecting panic if assertion failed
                        if (parent is GoShortVarDeclaration && parent.varDefinitionList.size == 2)
                            arrayOf<LocalQuickFix>(GoErrorTypeAssertionFix(element, document.getLineStartOffset(overrideLine))) to element.textRange
                        else NonAvailableFix
                    }
                issue.Text == "switch on an error will fail on wrapped errors. Use errors.Is to check for specific errors" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoSwitchStatement ->
                        arrayOf<LocalQuickFix>(GoBringToExplanationFix("https://golang.org/pkg/errors/#Is", "Example & Best practice")) to element.switchStart?.textRange
                    }
                issue.Text == "type switch on error will fail on wrapped errors. Use errors.As to check for specific errors" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoSwitchStatement ->
                        arrayOf<LocalQuickFix>(GoBringToExplanationFix("https://golang.org/pkg/errors/#As", "Example & Best practice")) to element.switchStart?.textRange
                    }
                issue.Text.startsWith("comparing with") ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoConditionalExpr ->
                        // at this point we have no way to tell if left hand is err and right hand is const
                        // need to optimistic assert that
                        arrayOf<LocalQuickFix>(GoReplaceWithErrorsIsFix(element)) to element.textRange
                    }
                else ->
                    NonAvailableFix
            }
}