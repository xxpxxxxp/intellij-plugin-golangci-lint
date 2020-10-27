package com.ypwang.plugin.handler

import com.goide.psi.*
import com.goide.psi.impl.GoElementFactory
import com.goide.quickfix.GoRenameToQuickFix
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.PsiCommentImpl
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.*

object GoCriticHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            when {
                issue.Text.startsWith("assignOp: replace") ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoAssignmentStatement ->
                        val (currentAssignment, replace) = extractQuote(issue.Text, 2)
                        if (element.text == currentAssignment) {
                            if (replace.endsWith("++") || replace.endsWith("--"))
                                arrayOf<LocalQuickFix>(GoReplaceElementFix(replace, element, GoIncDecStatement::class.java)) to element.textRange
                            else
                                arrayOf<LocalQuickFix>(GoReplaceElementFix(replace, element, GoAssignmentStatement::class.java)) to element.textRange
                        } else NonAvailableFix
                    }
                issue.Text.startsWith("sloppyLen:") ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoConditionalExpr ->
                        if (issue.Text.contains(element.text)) {
                            val searchPattern = "can be "
                            val replace = issue.Text.substring(issue.Text.indexOf(searchPattern) + searchPattern.length)
                            arrayOf<LocalQuickFix>(GoReplaceElementFix(replace, element, GoConditionalExpr::class.java)) to element.textRange
                        } else NonAvailableFix
                    }
                issue.Text.startsWith("unslice:") ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoIndexOrSliceExpr ->
                        if (issue.Text.contains(element.text) && element.expression != null)
                            arrayOf<LocalQuickFix>(GoReplaceExpressionFix(element.expression!!.text, element)) to element.textRange
                        else NonAvailableFix
                    }
                issue.Text.startsWith("captLocal:") ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoParamDefinition ->
                        val text = element.identifier.text
                        if (text[0].isUpperCase())
                            arrayOf<LocalQuickFix>(GoRenameToQuickFix(element, text[0].toLowerCase() + text.substring(1))) to element.identifier.textRange
                        else NonAvailableFix
                    }
                issue.Text.startsWith("underef:") ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoParenthesesExpr ->
                        if (element.expression is GoUnaryExpr)
                            arrayOf<LocalQuickFix>(GoDerefFix(element)) to element.textRange
                        else NonAvailableFix
                    }
                issue.Text.startsWith("wrapperFunc:") ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoCallExpr ->
                        if (element.expression.text.endsWith("Replace") && element.argumentList.expressionList.isNotEmpty()) {
                            val lastArgument = element.argumentList.expressionList.last().value?.integer
                            if (lastArgument != null && lastArgument < 0)
                                return@chainFindAndHandle arrayOf<LocalQuickFix>(GoWrapperFuncFix(element)) to element.expression.textRange
                        }
                        NonAvailableFix
                    }
                issue.Text.startsWith("exitAfterDefer:") ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoCallExpr ->
                        if (element.expression.text.contains(".Fatal")) {
                            return@chainFindAndHandle arrayOf(
                                    GoExitAfterDeferFix(element),
                                    GoBringToExplanationFix("https://quasilyte.dev/blog/post/log-fatal-vs-log-panic/", "Why?")
                            ) to element.expression.textRange
                        }
                        NonAvailableFix
                    }
                issue.Text == "elseif: can replace 'else {if cond {}}' with 'else if cond {}'" -> {
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoElseStatement ->
                        if (element.block?.statementList?.size == 1)
                            arrayOf<LocalQuickFix>(GoOutdentInnerIfFix(element)) to element.`else`.textRange
                        else NonAvailableFix
                    }
                }
                issue.Text == "singleCaseSwitch: should rewrite switch statement to if statement" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoSwitchStatement ->
                        // cannot handle type switch with var assign
                        val fix = if (element is GoTypeSwitchStatement && element.statement != null) EmptyLocalQuickFix
                        else arrayOf<LocalQuickFix>(GoSingleCaseSwitchFix(element))
                        fix to element.switchStart?.textRange
                    }
                issue.Text == "ifElseChain: rewrite if-else to switch statement" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoIfStatement ->
                        arrayOf<LocalQuickFix>(GoIfToSwitchFix(element)) to element.`if`.textRange
                    }
                issue.Text == "commentFormatting: put a space between `//` and comment text" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: PsiCommentImpl ->
                        if (element.text.startsWith("//"))
                            arrayOf<LocalQuickFix>(GoCommentFix(element, "Add space") { project, comment ->
                                GoElementFactory.createComment(project, "// " + comment.text.substring(2))
                            }) to element.textRange
                        else
                            NonAvailableFix
                    }
                else -> NonAvailableFix
            }
}