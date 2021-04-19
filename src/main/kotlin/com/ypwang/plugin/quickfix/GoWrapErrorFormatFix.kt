package com.ypwang.plugin.quickfix

import com.goide.psi.GoCallExpr
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoWrapErrorFormatFix(element: GoCallExpr, private val pos: Int)
    : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    companion object {
        private val verb = Regex("""%[+\-\d.# ]*[A-Za-z]""")
    }

    override fun getFamilyName(): String = text
    override fun getText(): String = "Format err with '%w'"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val element = startElement as GoCallExpr
        val format = element.argumentList.expressionList.first()
        val text = format.text

        var idx = element.argumentList.expressionList.withIndex().firstOrNull { pos in it.value.textRange }?.index ?: return
        var i = 0
        while (i < text.length && idx > 0) {
            val match = verb.find(text, i) ?: return
            idx--

            if (idx == 0) {
                val replaced = text.replaceRange(match.range, "%w")
                format.replace(GoElementFactory.createExpression(project, replaced))
            }
            i = match.range.last
        }
    }
}