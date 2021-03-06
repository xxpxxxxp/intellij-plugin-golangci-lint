package com.ypwang.plugin.handler

import com.goide.psi.GoFile
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.ypwang.plugin.model.LintIssue

abstract class ProblemHandler {
    companion object {
        val EmptyLocalQuickFix = arrayOf<LocalQuickFix>()
        val NonAvailableFix = EmptyLocalQuickFix to null
        // they reports issue of whole function
        val FuncLinters = setOf("funlen", "gocognit", "gochecknoinits", "gocyclo", "nakedret")
    }

    protected fun calcPos(document: Document, issue: LintIssue, overrideLine: Int): Int =
            // some linter reports whole line
            if (issue.Pos.Column == 0) document.getLineStartOffset(overrideLine)
            // Column is 1-based
            else document.getLineStartOffset(overrideLine) + issue.Pos.Column - 1

    protected inline fun <reified T : PsiElement> chainFindAndHandle(
            file: PsiFile,
            document: Document,
            issue: LintIssue,
            overrideLine: Int,
            handler: (T) -> Pair<Array<LocalQuickFix>, TextRange?>
    ): Pair<Array<LocalQuickFix>, TextRange?> {
        var element = file.findElementAt(calcPos(document, issue, overrideLine))
        while (true) {
            // jump out quicker
            when (element) {
                is T -> return handler(element)
                is GoFile, null -> return NonAvailableFix
                else -> element = element.parent
            }
        }
    }

    fun suggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange> {
        val (fix, range) = try {
            doSuggestFix(file, document, issue, overrideLine)
        } catch (e: Exception) {
            // ignore
            NonAvailableFix
        }

        return if (range != null) {
            fix to range
        } else {
            val pos = calcPos(document, issue, overrideLine)
            fix to TextRange.create(pos, maxOf(document.getLineEndOffset(overrideLine), pos))
        }
    }

    open fun description(issue: LintIssue): String = "${issue.Text} (${issue.FromLinter})"
    abstract fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?>
}