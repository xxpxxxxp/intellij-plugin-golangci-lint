package com.ypwang.plugin.handler

import com.goide.psi.*
import com.goide.quickfix.GoRenameToQuickFix
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
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
                issue.Text == "elseif: can replace 'else {if cond {}}' with 'else if cond {}'" -> {
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoElseStatement ->
                        if (element.block?.statementList?.size == 1)
                            arrayOf<LocalQuickFix>(GoOutdentInnerIfFix(element)) to element.textRange
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
                else -> NonAvailableFix
            }
}