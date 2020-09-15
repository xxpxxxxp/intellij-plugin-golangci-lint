package com.ypwang.plugin.quickfix

import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.PsiCommentImpl

class GoCommentFix(element: PsiCommentImpl, private val _text: String, private val createComment: (Project, PsiComment) -> PsiComment) : LocalQuickFixOnPsiElement(element) {
    override fun getFamilyName(): String = _text
    override fun getText(): String = _text

    override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
        startElement.replace(createComment(project, startElement as PsiComment))
    }
}