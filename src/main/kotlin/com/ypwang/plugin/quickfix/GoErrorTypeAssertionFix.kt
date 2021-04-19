package com.ypwang.plugin.quickfix

import com.goide.psi.GoShortVarDeclaration
import com.goide.psi.GoTypeAssertionExpr
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoErrorTypeAssertionFix(element: GoTypeAssertionExpr, private val lineStart: Int)
    : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    companion object {
        private var count = 0
    }

    override fun getFamilyName(): String = text
    override fun getText(): String = "Rewrite to errors.Is"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val element = startElement as GoTypeAssertionExpr
        val parent = element.parent as GoShortVarDeclaration

        val (variable, ok) = parent.varDefinitionList
        val varName =
            when (variable.identifier.text) {
                "_" -> "_t${count++}"         // anonymous
                element.expression.text -> "_${element.expression.text}"
                else -> variable.identifier.text
            }

        val replace = GoElementFactory.createElement(
                project,
                "package a; func a() {\n " + "${ok.identifier.text} := errors.Is(${element.expression.text}, $varName)" + "}",
                GoShortVarDeclaration::class.java)
        if (replace != null) {
            // add var definition before line
            val lineBreak = file.findElementAt(lineStart)?.prevSibling
            if (lineBreak?.parent == null)
                return

            val anchor = lineBreak.parent.addAfter(GoElementFactory.createVarDeclaration(project, "$varName ${element.type?.text}"), lineBreak)
            anchor.parent.addAfter(GoElementFactory.createNewLine(project), anchor)
            parent.replace(replace)
        }
    }
}