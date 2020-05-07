package com.ypwang.plugin.quickfix

import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.PsiCommentImpl

class GoDotFix(element: PsiCommentImpl): LocalQuickFixOnPsiElement(element) {
    override fun getFamilyName(): String = text

    override fun getText(): String = "Add '.' to the end"

    override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
        startElement.replace(GoElementFactory.createComment(project, startElement.text + "."))
    }
}