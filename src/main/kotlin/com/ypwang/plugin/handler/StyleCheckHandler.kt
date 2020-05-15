package com.ypwang.plugin.handler

import com.goide.psi.*
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.GoDecapitalizeStringFix
import com.ypwang.plugin.quickfix.GoReferenceRenameToBlankQuickFix
import com.ypwang.plugin.quickfix.GoReplaceInvisibleCharInStringFix
import com.ypwang.plugin.quickfix.GoSwapBinaryExprFix

object StyleCheckHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            when (issue.Text.substring(0, issue.Text.indexOf(':'))) {
                // error strings should not be capitalized
                "ST1005" -> {
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoArgumentList ->
                        val formatString = element.expressionList.first()
                        if (formatString is GoStringLiteral) arrayOf<LocalQuickFix>(GoDecapitalizeStringFix(formatString)) to formatString.textRange
                        else NonAvailableFix
                    }
                }
                // don't use Yoda conditions
                "ST1017" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoConditionalExpr ->
                        if (element.left is GoLiteral || element.left is GoStringLiteral) arrayOf<LocalQuickFix>(GoSwapBinaryExprFix(element)) to element.textRange
                        else NonAvailableFix
                    }
                // escape invisible character
                "ST1018" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoStringLiteral ->
                        val begin = issue.Text.indexOf('\'')
                        val end = issue.Text.indexOf('\'', begin + 1)
                        val utfChar = issue.Text.substring(begin + 1, end)
                        assert(utfChar.startsWith("\\u"))
                        arrayOf<LocalQuickFix>(GoReplaceInvisibleCharInStringFix(element, utfChar.substring(2).toInt(16))) to element.textRange
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