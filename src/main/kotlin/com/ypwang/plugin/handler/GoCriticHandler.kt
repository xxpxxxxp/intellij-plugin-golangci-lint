package com.ypwang.plugin.handler

import com.goide.psi.*
import com.goide.psi.impl.GoElementFactory
import com.goide.quickfix.GoRenameToQuickFix
import com.goide.quickfix.GoUnwrapParensQuickFix
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.LocalQuickFixOnPsiElementAsIntentionAdapter
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.PsiCommentImpl
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.*

object GoCriticHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<IntentionAction>, TextRange?> =
        find(patterns, issue.Text).map { it.handle(file, document, issue, overrideLine) }.orElse(NonAvailableFix)

    private fun interface Handler {
        fun handle(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<IntentionAction>, TextRange?>
    }

    private inline fun <reified T : PsiElement> addHandler(
        root: WordTree<Handler>,
        word: String,
        crossinline handler: (file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int, element: T) -> Pair<Array<IntentionAction>, TextRange?>
    ) {
        add(root,
            word,
            object: Handler {
                override fun handle(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<IntentionAction>, TextRange?> =
                    chainFindAndHandle<T>(file, document, issue, overrideLine) { handler.invoke(file, document, issue, overrideLine, it) }
            }
        )
    }

    private val patterns = WordTree<Handler>(entry = mutableMapOf())
        .apply {
            addHandler(this, "assignOp: replace") { _, _, issue, _, element: GoAssignmentStatement ->
                val (currentAssignment, replace) = extractQuote(issue.Text, 2)
                if (element.text == currentAssignment) {
                    if (replace.endsWith("++") || replace.endsWith("--"))
                        arrayOf<IntentionAction>(GoReplaceElementFix(replace, element, GoIncDecStatement::class.java)) to element.textRange
                    else
                        arrayOf<IntentionAction>(GoReplaceElementFix(replace, element, GoAssignmentStatement::class.java)) to element.textRange
                } else NonAvailableFix
            }

            addHandler(this, "sloppyLen:") { _, _, issue, _, element: GoConditionalExpr ->
                if (issue.Text.contains(element.text)) {
                    val searchPattern = "can be "
                    val replace = issue.Text.substring(issue.Text.indexOf(searchPattern) + searchPattern.length)
                    arrayOf<IntentionAction>(GoReplaceElementFix(replace, element, GoConditionalExpr::class.java)) to element.textRange
                } else NonAvailableFix
            }

            addHandler(this, "unslice:") { _, _, issue, _, element: GoIndexOrSliceExpr ->
                if (issue.Text.contains(element.text) && element.expression != null)
                    arrayOf<IntentionAction>(GoReplaceElementFix(element.expression!!.text, element, GoExpression::class.java)) to element.textRange
                else NonAvailableFix
            }

            addHandler(this, "captLocal:") { _, _, _, _, element: GoParamDefinition ->
                val text = element.identifier.text
                if (text[0].isUpperCase())
                    arrayOf<IntentionAction>(LocalQuickFixOnPsiElementAsIntentionAdapter(GoRenameToQuickFix(element, text[0].lowercaseChar() + text.substring(1)))) to element.identifier.textRange
                else NonAvailableFix
            }

            addHandler(this, "underef:") { _, _, _, _, element: GoParenthesesExpr ->
                if (element.expression is GoUnaryExpr)
                    arrayOf<IntentionAction>(GoDerefFix(element)) to element.textRange
                else NonAvailableFix
            }

            addHandler(this, "wrapperFunc:") l@{ _, _, _, _, element: GoCallExpr ->
                if (element.expression.text.endsWith("Replace") && element.argumentList.expressionList.isNotEmpty()) {
                    val lastArgument = element.argumentList.expressionList.last().value?.integer
                    if (lastArgument != null && lastArgument < 0)
                        return@l arrayOf<IntentionAction>(GoWrapperFuncFix(element)) to element.expression.textRange
                }
                NonAvailableFix
            }

            addHandler(this, "exitAfterDefer:") { _, _, _, _, element: GoCallExpr ->
                if (element.expression.text.contains(".Fatal")) {
                    arrayOf(
                        GoExitAfterDeferFix(element),
                        GoBringToExplanationFix("https://quasilyte.dev/blog/post/log-fatal-vs-log-panic/", "Why?")
                    ) to element.expression.textRange
                } else NonAvailableFix
            }

            val importShadowHandler: (PsiFile, Document, LintIssue, Int, GoNamedElement) -> Pair<Array<IntentionAction>, TextRange?> =
                { _, _, _, _, element ->
                    arrayOf<IntentionAction>(GoRefactorFix(element)) to element.identifier?.textRange
                }
            addHandler(this, "builtinShadow:", importShadowHandler)
            addHandler(this, "importShadow:", importShadowHandler)

            addHandler(this, "deferUnlambda:") { _, _, _, _, element: GoDeferStatement ->
                if (element.expression is GoCallExpr && (element.expression as GoCallExpr).expression is GoFunctionLit) {
                    arrayOf<IntentionAction>(GoDeferUnlambdaFix(element)) to element.defer.textRange
                } else NonAvailableFix
            }

            addHandler(this, "typeUnparen:") { _, _, _, _, element: GoParenthesesExpr ->
                arrayOf<IntentionAction>(GoUnwrapParensQuickFix(element)) to element.textRange
            }

            addHandler(this, "regexpSimplify:") { _, _, issue, _, element: GoStringLiteral ->
                val (origin, replace) = extractQuote(issue.Text, 2)
                if (origin == element.decodedText) {
                    arrayOf<IntentionAction>(
                        GoReplaceElementFix(
                            "`$replace`",
                            element,
                            GoStringLiteral::class.java
                        )
                    ) to element.textRange
                } else NonAvailableFix
            }

            addHandler(this, "nilValReturn:") { _, _, _, _, element: GoReturnStatement ->
                arrayOf<IntentionAction>(
                    GoReplaceElementFix(
                        "return nil",
                        element,
                        GoReturnStatement::class.java
                    )
                ) to element.textRange
            }

            addHandler(this, "sloppyReassign:") { _, _, _, _, element: GoAssignmentStatement ->
                arrayOf<IntentionAction>(
                    GoReplaceElementFix(
                        "${element.leftHandExprList.text} := ${element.expressionList.joinToString(", ") { it.text }}",
                        element,
                        GoSimpleStatement::class.java
                    )
                ) to element.leftHandExprList.textRange
            }

            addHandler(this, "paramTypeCombine:") { _, _, _, _, element: GoFunctionOrMethodDeclaration ->
                arrayOf<IntentionAction>(GoShortenParameterFix(element)) to element.signature!!.textRange
            }

            addHandler(this, "dupImport:") { file, _, _, _, element: GoImportSpec ->
                if ((file as GoFile).imports.filter { it.path == element.path }
                        .all { !it.isForSideEffects && !it.isDot })
                    arrayOf<IntentionAction>(GoRemoveDuplImportFix(element)) to element.textRange
                else NonAvailableFix
            }

            addHandler(this, "equalFold:") { _, _, issue, _, element: GoConditionalExpr ->
                arrayOf<IntentionAction>(GoReplaceElementFix(issue.Text.substring(35), element, GoExpression::class.java)) to element.textRange
            }

            addHandler(this, "yodaStyleExpr:") { _, _, _, _, element: GoConditionalExpr ->
                if (element.left is GoLiteral || element.left is GoStringLiteral) arrayOf<IntentionAction>(
                    GoSwapBinaryExprFix(element)
                ) to element.textRange
                else NonAvailableFix
            }

            addHandler(this, "elseif: can replace 'else {if cond {}}' with 'else if cond {}'") { _, _, _, _, element: GoElseStatement ->
                    if (element.block?.statementList?.size == 1)
                        arrayOf<IntentionAction>(GoOutdentInnerIfFix(element)) to element.`else`.textRange
                    else NonAvailableFix
            }

            addHandler(this, "singleCaseSwitch: should rewrite switch statement to if statement") { _, _, _, _, element: GoSwitchStatement ->
                // cannot handle type switch with var assign
                val fix = if (element is GoTypeSwitchStatement && element.statement != null) EmptyLocalQuickFix
                else arrayOf<IntentionAction>(GoSingleCaseSwitchFix(element))
                fix to element.switchStart?.textRange
            }

            addHandler(this, "ifElseChain: rewrite if-else to switch statement") { _, _, _, _, element: GoIfStatement ->
                arrayOf<IntentionAction>(GoIfToSwitchFix(element)) to element.`if`.textRange
            }

            addHandler(this, "commentFormatting: put a space between `//` and comment text") { _, _, _, _, element: PsiCommentImpl ->
                if (element.text.startsWith("//"))
                    arrayOf<IntentionAction>(GoCommentFix(element, "Add space") { project, comment ->
                        GoElementFactory.createComment(project, "// " + comment.text.substring(2))
                    }) to element.textRange
                else NonAvailableFix
            }

            addHandler(this, "unlambda: replace") { _, _, issue, _, element: GoFunctionLit ->
                val (_, replace) = extractQuote(issue.Text, 2)
                arrayOf<IntentionAction>(GoReplaceElementFix(replace, element, GoExpression::class.java)) to element.textRange
            }

            addHandler(this, "sloppyTypeAssert:") { _, _, _, _, element: GoTypeAssertionExpr ->
                arrayOf<IntentionAction>(GoReplaceElementFix(element.expression.text, element, GoExpression::class.java)) to element.textRange
            }

            addHandler(this, "badCall:") { _, _, _, _, element: GoCallExpr ->
                arrayOf<IntentionAction>(GoEscapeCallExprFix(element)) to element.textRange
            }
        }
}