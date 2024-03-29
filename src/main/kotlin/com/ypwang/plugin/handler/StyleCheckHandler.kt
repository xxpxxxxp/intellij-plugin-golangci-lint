package com.ypwang.plugin.handler

import com.goide.psi.*
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.GoRemoveDuplImportFix
import com.ypwang.plugin.quickfix.GoReplaceStringFix
import com.ypwang.plugin.quickfix.GoSwapBinaryExprFix
import org.apache.commons.lang.StringEscapeUtils
import java.util.*

object StyleCheckHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<IntentionAction>, TextRange?> =
            when (issue.Text.substring(0, issue.Text.indexOf(':'))) {
                // error strings should not be capitalized
                "ST1005" -> {
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoCallExpr ->
                        val formatString = element.argumentList.expressionList.first()
                        if (formatString is GoStringLiteral) arrayOf<IntentionAction>(GoReplaceStringFix("Decapitalize string", formatString){
                            "\"${StringEscapeUtils.escapeJava(it.replaceFirstChar { c -> c.lowercase(Locale.getDefault()) })}\""
                        }) to formatString.textRange
                        else NonAvailableFix
                    }
                }
                // don't use Yoda conditions
                "ST1017" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoConditionalExpr ->
                        if (element.left is GoLiteral || element.left is GoStringLiteral) arrayOf<IntentionAction>(GoSwapBinaryExprFix(element)) to element.textRange
                        else NonAvailableFix
                    }
                // escape invisible character
                "ST1018" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoStringLiteral ->
                        val begin = issue.Text.indexOf('\'')
                        val end = issue.Text.indexOf('\'', begin + 1)
                        val utfChar = issue.Text.substring(begin + 1, end)
                        assert(utfChar.startsWith("\\u"))
                        arrayOf<IntentionAction>(GoReplaceStringFix("Escape string", element) {
                            val hex = utfChar.substring(2).toInt(16)
                            val sb = StringBuilder()
                            for (c in it) {
                                if (c.code == hex) sb.append("\\u${c.code.toString(16)}")
                                else sb.append(c)
                            }
                            "\"$sb\""
                        }) to element.textRange
                    }
                // package "xxx" is being imported more than once
                "ST1019" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoImportSpec ->
                        if ((file as GoFile).imports.filter { it.path == element.path }.all { !it.isForSideEffects && !it.isDot })
                            arrayOf<IntentionAction>(GoRemoveDuplImportFix(element)) to element.textRange
                        else
                            NonAvailableFix
                    }
                else -> NonAvailableFix
            }
}