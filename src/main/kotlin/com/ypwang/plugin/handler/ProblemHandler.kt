package com.ypwang.plugin.handler

import com.goide.psi.GoFile
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.NoLintSingleLineCommentFix

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
            if (element is GoFile || element == null)
                return NonAvailableFix
            if (element is T)
                return handler(element)
            element = element.parent
        }
    }

    fun suggestFix(linter: String, file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange> {
        val fix = try {
            var (_fix, range) = doSuggestFix(file, document, issue, overrideLine)

            // generally, if there's quick fix available, we won't suggest nolint
            // and func linters will be take care separately
            if (_fix.isEmpty() && linter !in FuncLinters)
                _fix = arrayOf(NoLintSingleLineCommentFix(linter))

            if (range != null)
                return _fix to range

            _fix
        } catch (e: Exception) {
            // ignore
            EmptyLocalQuickFix
        }

        val pos = calcPos(document, issue, overrideLine)
        return fix to TextRange.create(pos, maxOf(document.getLineEndOffset(overrideLine), pos))
    }

    open fun description(issue: LintIssue): String = "${issue.Text} (${issue.FromLinter})"
    abstract fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?>
}