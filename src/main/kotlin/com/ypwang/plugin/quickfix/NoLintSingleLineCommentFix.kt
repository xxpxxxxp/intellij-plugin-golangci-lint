package com.ypwang.plugin.quickfix

import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

class NoLintSingleLineCommentFix(
    private val linter: String,
    private val lineNumber: Int
    ) : IntentionAction {
    override fun getText(): String = "Suppress linter '$linter' here"
    override fun getFamilyName(): String = text
    override fun startInWriteAction(): Boolean = true
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (file == null)
            return

        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        // trick: for a specific line, line comment must at end of line (right before \n)
        val element = file.findElementAt(document.getLineEndOffset(lineNumber))?.prevSibling ?: return
        if (element is PsiComment) {
            // replace
            val text = element.text
            val replace =
                if (text.startsWith("//nolint:")) "//nolint:$linter,${text.substring(9)}"
                else "//nolint:$linter    $text"

            createComment(project, replace)?.also { element.replace(it) }
        } else {
            // just add to end
            createComment(project, "//nolint:$linter")?.also { element.parent.addAfter(it, element) }
        }
    }

    private fun createComment(project: Project, text: String): PsiComment? =
        GoElementFactory.createElement(
                project,
                "package a; \n $text\n}",
                PsiComment::class.java)
}