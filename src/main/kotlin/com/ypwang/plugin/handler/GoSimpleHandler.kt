package com.ypwang.plugin.handler

import com.goide.psi.*
import com.goide.quickfix.GoDeleteRangeQuickFix
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.refactoring.suggested.endOffset
import com.intellij.refactoring.suggested.startOffset
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.*

object GoSimpleHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            when (issue.Text.substring(0, issue.Text.indexOf(':'))) {
                // should use !strings.Contains(domain, \".\") instead
                // should use !bytes.Equal(meta.CRC, computed) instead
                "S1003", "S1004" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoSimpleStatement ->
                        if (element.leftHandExprList != null)
                            arrayOf<LocalQuickFix>(GoReplaceStatementFix(issue.Text.substring(18, issue.Text.length - 8), element)) to element.textRange
                        else NonAvailableFix
                    }
                "S1005" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoShortVarDeclaration ->
                        if (element.varDefinitionList.size == 2 && element.varDefinitionList.last().text == "_")
                            arrayOf<LocalQuickFix>(
                                    GoDeleteRangeQuickFix(element.varDefinitionList.first().nextSibling, element.varDefinitionList.last(), "Remove '_'")
                            ) to element.varDefinitionList.last().textRange
                        else NonAvailableFix
                    }
                // should use for {} instead of for true {}
                "S1006" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoForStatement ->
                        if (element.expression?.text == "true")
                            arrayOf<LocalQuickFix>(GoDeleteElementsFix(listOf(element.expression!!), "Remove condition 'true'")) to element.expression!!.textRange
                        else NonAvailableFix
                    }
                // should use raw string (`...`) with regexp.MustCompile to avoid having to escape twice
                "S1007" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoCallExpr ->
                        if (element.expression.text.let { it == "regexp.MustCompile" || it == "regexp.Compile" } &&
                                element.argumentList.expressionList.firstOrNull() is GoStringLiteral)
                            element.argumentList.expressionList.first().let {
                                arrayOf<LocalQuickFix>(GoReplaceStringFix("Use raw string", it as GoStringLiteral){ s -> "`$s`" }) to it.textRange
                            }
                        else NonAvailableFix
                    }
                // should use 'return v == 1' instead of 'if v == 1 { return true }; return false'
                "S1008" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoIfStatement ->
                        var cur = element.nextSibling
                        while (cur is PsiWhiteSpace)
                            cur = cur.nextSibling

                        if (cur is GoReturnStatement) {
                            val begin = issue.Text.indexOf('\'')
                            val end = issue.Text.indexOf('\'', begin + 1)
                            val replace = issue.Text.substring(begin + 1, end)

                            val textRange = TextRange(element.startOffset, cur.endOffset)
                            arrayOf<LocalQuickFix>(GoSimplifyIfReturnFix(element, cur, replace)) to textRange
                        }
                        else NonAvailableFix
                    }
                // should use `time.Since` instead of `time.Now().Sub`
                "S1012" -> chainFindAndHandle(file, document, issue, overrideLine) { element: GoReferenceExpression ->
                    if (element.parent is GoReferenceExpression &&
                            element.parent.parent is GoCallExpr) {
                        val expr = element.parent.parent.parent
                        if (expr is GoReferenceExpression && expr.text == "time.Now().Sub")
                            return arrayOf<LocalQuickFix>(GoReplaceExpressionFix("time.Since", expr)) to expr.textRange
                    }
                    NonAvailableFix
                }
                // should merge variable declaration with assignment on next line
                "S1021" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoVarDeclaration ->
                        if (element.varSpecList.size > 1) return NonAvailableFix
                        val parent = element.parent
                        if (parent !is GoStatement) return NonAvailableFix

                        var cur = parent.nextSibling
                        while (cur is PsiWhiteSpace)
                            cur = cur.nextSibling

                        if (cur !is GoAssignmentStatement) return NonAvailableFix
                        val assignment = cur as GoAssignmentStatement
                        if (assignment.leftHandExprList.expressionList.size != 1 ||
                                assignment.leftHandExprList.expressionList.single().text != element.varSpecList.single().definitionList.single().text ||
                                assignment.expressionList.size != 1)
                            return NonAvailableFix

                        val textRange = TextRange(parent.startOffset, cur.endOffset)
                        arrayOf<LocalQuickFix>(GoConvertToShortVarFix(element, cur, assignment.leftHandExprList.expressionList.single().text, assignment.expressionList.single().text)) to textRange
                    }
                // redundant `return` statement
                "S1023" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoReturnStatement ->
                        if (element.expressionList.isEmpty())
                            arrayOf<LocalQuickFix>(GoDeleteElementsFix(listOf(element.nextSibling, element), "Remove redundant 'return'")) to element.textRange
                        else NonAvailableFix
                    }
                // should use w.buff.String() instead of string(w.buff.Bytes())
                "S1030" ->
                    arrayOf<LocalQuickFix>(GoBringToExplanationFix(
                            "https://github.com/dominikh/go-tools/issues/723",
                            "See 'S1030: don't flag m[string(buf.Bytes()]'")) to null
                // unnecessary use of fmt.Sprintf
                "S1039" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoCallExpr ->
                        if (element.argumentList.expressionList.firstOrNull() is GoStringLiteral)
                            arrayOf<LocalQuickFix>(GoEscapeCallExprFix(element)) to element.textRange
                        else NonAvailableFix
                    }
                else -> NonAvailableFix
            }
}