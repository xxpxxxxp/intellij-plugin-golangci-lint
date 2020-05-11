package com.ypwang.plugin.quickfix

import com.goide.psi.GoElseStatement
import com.goide.psi.impl.GoElementFactory
import com.goide.quickfix.GoSimplifyIfStatementUtil
import com.goide.refactor.util.GoRefactoringUtil
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.codeStyle.CodeEditUtil
import com.intellij.util.SmartList
import com.intellij.util.containers.ContainerUtil

class GoOutdentElseFix(element: GoElseStatement): LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = text

    override fun getText(): String = "Outdent 'else' branch"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val elseStatement = startElement as GoElseStatement
        val parent = elseStatement.parent
        if (elseStatement.block == null || parent == null)
            return

        GoRefactoringUtil.withVerticalScrollingSaved(editor) {
            val results = SmartList(parent.addBefore(GoElementFactory.createNewLine(project), elseStatement))
            GoSimplifyIfStatementUtil.processElementsWithComments(elseStatement.block!!.statementList) {
                results.add(parent.addBefore(it, elseStatement))
            }
            results.forEach { CodeEditUtil.markToReformat(it.node, true) }
            elseStatement.delete()
            val firstResult = ContainerUtil.getFirstItem(results)
            if (editor != null && firstResult != null) {
                editor.caretModel.moveToOffset(firstResult.textRange.startOffset)
            }

            GoRefactoringUtil.highlightSearchResults(project, editor, results)
        }
    }
}