package com.ypwang.plugin.quickfix

import com.goide.psi.GoElseStatement
import com.goide.psi.GoIfStatement
import com.goide.psi.GoStatement
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

class GoOutdentElseFix(element: GoStatement)
    : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = text
    override fun getText(): String = "Outdent 'else' branch"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val elseStatement =
                when (startElement) {
                    is GoIfStatement -> {
                        val initStatement = startElement.initStatement
                        if (initStatement != null) {
                            startElement.parent.addBefore(initStatement.copy(), startElement)
                        }
                        startElement.initStatement?.delete()
                        startElement.semicolon?.delete()
                        startElement.elseStatement!!
                    }
                    is GoElseStatement -> startElement
                    else -> return
                }

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