package com.ypwang.plugin.quickfix

import com.goide.psi.GoConstDeclaration
import com.goide.psi.GoConstSpec
import com.goide.psi.GoFile
import com.goide.psi.GoStringLiteral
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.refactoring.RefactoringActionHandlerFactory
import com.intellij.refactoring.rename.RenameHandlerRegistry

class GoIntroduceConstStringLiteralFix(
        private val file: GoFile,
        private val literal: String
) : LocalQuickFix {
    override fun getFamilyName(): String = "Introduce const string $literal"

    // Buggy, need improve
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        try {
            val content = FileDocumentManager.getInstance().getDocument(file.virtualFile)?.charsSequence ?: return

            val offsets = mutableListOf<Int>()
            var pos = -1

            while (true) {
                pos = content.indexOf(literal, pos + 1)
                if (pos == -1) break
                offsets.add(pos)
            }

            if (offsets.isEmpty()) return
            val editor = (FileEditorManager.getInstance(project).selectedEditor as TextEditor?)?.editor ?: return

            val replace = GoElementFactory.createReferenceExpression(project, "_a_good_name")
            // be carefully with reversed, since replacement above will change offset
            for (offset in offsets.reversed()) {
                var element = file.findElementAt(offset)
                while (element != null) {
                    if (element is GoStringLiteral) {
                        if (element.text == literal) element.replace(replace)
                        break
                    }

                    element = element.parent
                }
            }

            // put it after the last const decl, or after import if no not found
            val defined = file.children.lastOrNull { it is GoConstDeclaration }
            val constSpec: GoConstSpec =
                    if (defined != null) {
                        // add in last const decl scope
                        (defined as GoConstDeclaration).addSpec("_a_good_name", null, literal, null)
                    } else {
                        // add after import
                        val decl = GoElementFactory.createConstDeclaration(project, "_a_good_name = $literal")
                        (file.addAfter(decl, file.importList) as GoConstDeclaration).constSpecList.single()
                    }

            // move caret, focus on newly created const
            editor.caretModel.moveToOffset(constSpec.definitionList.first().identifier.textOffset)
            // finally, perform rename on the replaced literal
            DataManager.getInstance().dataContextFromFocusAsync.onSuccess { dataContext: DataContext? ->
                ApplicationManager.getApplication().invokeLater({
                    val renameHandler = RenameHandlerRegistry.getInstance().getRenameHandler(dataContext!!)
                    if (renameHandler != null) {
                        renameHandler.invoke(project, arrayOf(constSpec), dataContext)
                    } else {
                        val renameRefactoringHandler = RefactoringActionHandlerFactory.getInstance().createRenameHandler()
                        renameRefactoringHandler.invoke(project, arrayOf(constSpec), dataContext)
                    }
                }, ModalityState.NON_MODAL)
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}