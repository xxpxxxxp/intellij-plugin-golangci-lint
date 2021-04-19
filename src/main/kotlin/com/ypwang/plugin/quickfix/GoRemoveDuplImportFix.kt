package com.ypwang.plugin.quickfix

import com.goide.psi.GoFile
import com.goide.psi.GoImportSpec
import com.goide.psi.GoReferencesSearch
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoRemoveDuplImportFix(element: GoImportSpec)
    : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = text
    override fun getText(): String = "Merge import"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val element = startElement as GoImportSpec

        if (file is GoFile) {
            val dups = file.imports.filter { it.path == element.path }

            val defaultImport = dups.singleOrNull { it.alias == null }
            val (single, others) =
                if (defaultImport != null) {
                    val pathBreak = defaultImport.path.lastIndexOf('/')
                    defaultImport.path.substring(pathBreak + 1) to dups.filter { it.alias != null }
                } else {
                    element.alias to dups.filter { it.alias != element.alias }
                }

            for (goImport in others) {
                for (reference in GoReferencesSearch.search(goImport).findAll()) {
                    reference.handleElementRename(single)
                }

                goImport.delete()
            }
        }
    }
}