package com.ypwang.plugin.handler

import com.goide.psi.GoCallExpr
import com.goide.psi.GoConditionalExpr
import com.goide.psi.GoLiteral
import com.goide.psi.GoStringLiteral
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.GoReplaceStringFix
import com.ypwang.plugin.quickfix.GoSwapBinaryExprFix
import org.apache.commons.lang.StringEscapeUtils

object StyleCheckHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            when (issue.Text.substring(0, issue.Text.indexOf(':'))) {
                // error strings should not be capitalized
                "ST1005" -> {
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoCallExpr ->
                        val formatString = element.argumentList.expressionList.first()
                        if (formatString is GoStringLiteral) arrayOf<LocalQuickFix>(GoReplaceStringFix("Decapitalize string", formatString){
                            "\"${StringEscapeUtils.escapeJava(it.decapitalize())}\""
                        }) to formatString.textRange
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
                        arrayOf<LocalQuickFix>(GoReplaceStringFix("Escape string", element) {
                            val hex = utfChar.substring(2).toInt(16)
                            val sb = StringBuilder()
                            for (c in it) {
                                if (c.toInt() == hex) sb.append("\\u${c.toInt().toString(16)}")
                                else sb.append(c)
                            }
                            "\"$sb\""
                        }) to element.textRange
                    }
                else -> NonAvailableFix
            }
}