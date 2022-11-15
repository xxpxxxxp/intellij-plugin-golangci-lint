package com.ypwang.plugin.handler

import com.goide.psi.*
import com.goide.quickfix.GoDeleteRangeQuickFix
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.intellij.refactoring.suggested.endOffset
import com.intellij.refactoring.suggested.startOffset
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.*

object GoSimpleHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<IntentionAction>, TextRange?> =
            when (issue.Text.substring(0, issue.Text.indexOf(':'))) {
                // should use a simple channel send/receive instead of `select` with a single case
                "S1000" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoSelectStatement ->
                        // it's safe to rewrite to if statement, to keeping variable life circles
                        arrayOf<IntentionAction>(GoSimplifySimpleChanSelectFix(element)) to element.select.textRange
                    }
                "S1001" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoForStatement ->
                        if (element.rangeClause != null && element.block?.statementList?.singleOrNull() is GoAssignmentStatement) {
                            val source = element.rangeClause!!.rangeExpression!!.text
                            val dest = ((element.block!!.statementList.single() as GoAssignmentStatement)
                                .leftHandExprList
                                .expressionList
                                .single() as GoIndexOrSliceExpr)
                                .expression!!.text

                            arrayOf<IntentionAction>(GoReplaceElementFix("copy($dest, $source)", element, GoExpression::class.java)) to element.`for`.textRange
                        } else
                            NonAvailableFix
                    }
                // should omit comparison to bool constant, can be simplified to `...`
                "S1002" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoConditionalExpr ->
                        val begin = issue.Text.indexOf('`')
                        val end = issue.Text.indexOf('`', begin + 1)
                        val replace = issue.Text.substring(begin + 1, end)
                        arrayOf<IntentionAction>(GoReplaceElementFix(replace, element, GoExpression::class.java)) to element.textRange
                    }
                // should use !strings.Contains(domain, \".\") instead
                // should use !bytes.Equal(meta.CRC, computed) instead
                "S1003", "S1004" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoSimpleStatement ->
                        if (element.leftHandExprList != null)
                            arrayOf<IntentionAction>(GoReplaceElementFix(issue.Text.substring(18, issue.Text.length - 8), element, GoStatement::class.java)) to element.textRange
                        else NonAvailableFix
                    }
                "S1005" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoShortVarDeclaration ->
                        if (element.varDefinitionList.size == 2 && element.varDefinitionList.last().text == "_")
                            arrayOf<IntentionAction>(
                                    GoDeleteRangeQuickFix(element.varDefinitionList.first().nextSibling, element.varDefinitionList.last(), "Remove '_'")
                            ) to element.varDefinitionList.last().textRange
                        else NonAvailableFix
                    }
                // should use for {} instead of for true {}
                "S1006" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoForStatement ->
                        if (element.expression?.text == "true")
                            arrayOf<IntentionAction>(GoDeleteElementFix(element.expression!!, "Remove condition 'true'")) to element.expression!!.textRange
                        else NonAvailableFix
                    }
                // should use raw string (`...`) with regexp.MustCompile to avoid having to escape twice
                "S1007" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoCallExpr ->
                        if (element.expression.text.let { it == "regexp.MustCompile" || it == "regexp.Compile" } &&
                                element.argumentList.expressionList.firstOrNull() is GoStringLiteral)
                            element.argumentList.expressionList.first().let {
                                arrayOf<IntentionAction>(GoReplaceStringFix("Use raw string", it as GoStringLiteral){ s -> "`$s`" }) to it.textRange
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
                            arrayOf<IntentionAction>(GoSimplifyIfReturnFix(element, cur, replace)) to textRange
                        }
                        else NonAvailableFix
                    }
                // should omit nil check; len() for xxxx is defined as zero
                "S1009" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoOrExpr ->
                        return arrayOf<IntentionAction>(GoReplaceElementFix(element.right!!.text, element, GoExpression::class.java)) to element.textRange
                    }
                // should replace loop with `...`"
                "S1011" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoForStatement ->
                        return arrayOf<IntentionAction>(GoReplaceElementFix(extractQuote(issue.Text).single(), element, GoAssignmentStatement::class.java)) to element.`for`.textRange
                    }
                // should use `time.Since` instead of `time.Now().Sub`
                "S1012" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoReferenceExpression ->
                        if (element.parent is GoReferenceExpression &&
                                element.parent.parent is GoCallExpr) {
                            val expr = element.parent.parent.parent
                            if (expr is GoReferenceExpression && expr.text == "time.Now().Sub")
                                return arrayOf<IntentionAction>(GoReplaceElementFix("time.Since", expr, GoExpression::class.java)) to expr.textRange
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
                        arrayOf<IntentionAction>(GoConvertToShortVarFix(element, cur, assignment.leftHandExprList.expressionList.single().text, assignment.expressionList.single().text)) to textRange
                    }
                // redundant `return` statement
                "S1023" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoReturnStatement ->
                        if (element.expressionList.isEmpty()) {
                            var start: PsiElement = element.prevSibling
                            while (start !is PsiWhiteSpaceImpl || start.text != "\n")
                                start = start.prevSibling

                            arrayOf<IntentionAction>(GoDeleteRangeQuickFix(start, element, "Remove redundant 'return'")) to element.textRange
                        }
                        else NonAvailableFix
                    }
                // should use w.buff.String() instead of string(w.buff.Bytes())
                "S1030" ->
                    arrayOf<IntentionAction>(GoBringToExplanationFix(
                            "https://github.com/dominikh/go-tools/issues/723",
                            "See 'S1030: don't flag m[string(buf.Bytes()]'")) to null
                // S1038: should use b.Fatalf(...) instead of b.Fatal(fmt.Sprintf(...))
                "S1038" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoCallExpr ->
                        if (element.argumentList.expressionList.size == 1 && element.argumentList.expressionList.single()!! is GoCallExpr)
                            arrayOf<IntentionAction>(GoReplaceElementFix(
                                "${element.expression.text}f${(element.argumentList.expressionList.single() as GoCallExpr).argumentList.text}",
                                element, GoCallExpr::class.java)) to element.textRange
                        else NonAvailableFix
                    }
                // unnecessary use of fmt.Sprintf
                "S1039" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoCallExpr ->
                        if (element.argumentList.expressionList.firstOrNull() is GoStringLiteral)
                            arrayOf<IntentionAction>(GoEscapeCallExprFix(element)) to element.textRange
                        else NonAvailableFix
                    }
                // type assertion to the same type
                "S1040" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoTypeAssertionExpr ->
                        arrayOf<IntentionAction>(GoReplaceElementFix(element.expression.text, element, GoExpression::class.java)) to element.textRange
                    }
                else -> NonAvailableFix
            }
}