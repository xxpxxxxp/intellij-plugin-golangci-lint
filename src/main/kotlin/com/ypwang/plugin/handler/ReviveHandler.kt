package com.ypwang.plugin.handler

import com.goide.psi.GoIfStatement
import com.goide.psi.GoReturnStatement
import com.goide.psi.GoSimpleStatement
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.GoReplaceElementFix

object ReviveHandler : GolintHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<IntentionAction>, TextRange?> {
        val splitter = issue.Text.indexOf(": ")
        if (splitter == -1)
            return NonAvailableFix

        return when (issue.Text.substring(0, splitter)) {
            "if-return" ->
                chainFindAndHandle(file, document, issue, overrideLine) { element: GoIfStatement ->
                    if (element.initStatement is GoSimpleStatement && (element.initStatement as GoSimpleStatement).shortVarDeclaration?.expressionList?.isNotEmpty() == true)
                        arrayOf<IntentionAction>(
                            GoReplaceElementFix(
                                "return ${(element.initStatement as GoSimpleStatement).shortVarDeclaration!!.expressionList.first().text}",
                                element,
                                GoReturnStatement::class.java
                            )
                        ) to element.`if`.textRange
                    else NonAvailableFix
                }
            else ->
                suggestFix(issue.Text.substring(splitter + 2), file, document, issue, overrideLine)
        }
    }
}