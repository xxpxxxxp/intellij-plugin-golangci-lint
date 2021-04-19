package com.ypwang.plugin.quickfix

import com.goide.psi.GoNamedElement
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.RefactoringActionHandlerFactory
import com.intellij.refactoring.rename.RenameHandlerRegistry

class GoRefactorFix(element: GoNamedElement)
    : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = text
    override fun getText(): String = "Renaming..."

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        if (editor == null)
            return

        val element = startElement as GoNamedElement
        // move caret, focus on newly created const
        editor.caretModel.moveToOffset(element.identifier!!.textOffset)
        // finally, perform rename on the replaced literal
        DataManager.getInstance().dataContextFromFocusAsync.onSuccess { dataContext: DataContext? ->
            ApplicationManager.getApplication().invokeLater({
                val renameHandler = RenameHandlerRegistry.getInstance().getRenameHandler(dataContext!!)
                if (renameHandler != null) {
                    renameHandler.invoke(project, arrayOf(element), dataContext)
                } else {
                    val renameRefactoringHandler = RefactoringActionHandlerFactory.getInstance().createRenameHandler()
                    renameRefactoringHandler.invoke(project, arrayOf(element), dataContext)
                }
            }, ModalityState.NON_MODAL)
        }
    }
}