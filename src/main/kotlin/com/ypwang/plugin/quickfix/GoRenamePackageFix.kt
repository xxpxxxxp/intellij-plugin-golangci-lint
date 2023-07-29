package com.ypwang.plugin.quickfix

import com.goide.psi.GoPackageClause
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoRenamePackageFix(element: GoPackageClause, private val replace: String)
    : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = text
    override fun getText(): String = "Rename to '$replace' (caution: private fields are inaccessible then)"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        startElement.replace(GoElementFactory.createFileFromText(project, "package $replace").getPackage()!!)
    }
}