package com.ypwang.plugin.handler

import com.goide.inspections.GoInspectionUtil
import com.goide.psi.*
import com.goide.quickfix.GoDeleteRangeQuickFix
import com.goide.quickfix.GoRenameToBlankQuickFix
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.LocalQuickFixOnPsiElementAsIntentionAdapter
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.GoDeleteConstDefinitionFix
import com.ypwang.plugin.quickfix.GoDeleteElementFix
import com.ypwang.plugin.quickfix.GoDeleteVarDefinitionFix

object NamedElementHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<IntentionAction>, TextRange?> =
            chainFindAndHandle(file, document, issue, overrideLine) { element: GoNamedElement ->
                when (element) {
                    is GoFieldDefinition -> {
                        val decl = element.parent
                        if (decl is GoFieldDeclaration) {
                            var start: PsiElement = decl
                            while (start.prevSibling != null && (start.prevSibling !is PsiWhiteSpaceImpl || start.prevSibling.text != "\n"))
                                start = start.prevSibling

                            var end: PsiElement = decl.nextSibling
                            while (end !is PsiWhiteSpaceImpl || end.text != "\n")
                                end = end.nextSibling

                            // remove entire line
                            arrayOf<IntentionAction>(GoDeleteRangeQuickFix(start, end, "Delete field '${element.identifier.text}'"))
                        } else arrayOf()
                    }
                    is GoFunctionDeclaration ->
                        arrayOf(GoDeleteElementFix(element, "Delete function"), LocalQuickFixOnPsiElementAsIntentionAdapter(GoRenameToBlankQuickFix(element)))
                    is GoTypeSpec ->
                        arrayOf<IntentionAction>(GoDeleteElementFix(element, "Delete type"))
                    is GoVarDefinition ->
                        (if (GoInspectionUtil.canDeleteDefinition(element)) arrayOf(LocalQuickFixOnPsiElementAsIntentionAdapter(GoRenameToBlankQuickFix(element)), GoDeleteVarDefinitionFix(element))
                        else arrayOf<IntentionAction>(LocalQuickFixOnPsiElementAsIntentionAdapter(GoRenameToBlankQuickFix(element))))
                    is GoConstDefinition ->
                        (if (GoInspectionUtil.canDeleteDefinition(element)) arrayOf<IntentionAction>(LocalQuickFixOnPsiElementAsIntentionAdapter(GoDeleteConstDefinitionFix(element))) else arrayOf())
                    is GoMethodDeclaration ->
                        arrayOf<IntentionAction>(GoDeleteElementFix(element, "Delete method"))
                    else -> EmptyLocalQuickFix
                } to element.identifier?.textRange
            }
}