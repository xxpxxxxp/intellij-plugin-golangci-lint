package com.ypwang.plugin.handler

import com.goide.inspections.GoInspectionUtil
import com.goide.psi.*
import com.goide.quickfix.GoDeleteConstDefinitionQuickFix
import com.goide.quickfix.GoDeleteQuickFix.Fixes.DELETE_FUNCTION_FIX
import com.goide.quickfix.GoDeleteQuickFix.Fixes.DELETE_TYPE_FIX
import com.goide.quickfix.GoDeleteRangeQuickFix
import com.goide.quickfix.GoDeleteVarDefinitionQuickFix
import com.goide.quickfix.GoRenameToBlankQuickFix
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.ypwang.plugin.model.LintIssue

object NamedElementHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
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
                            arrayOf<LocalQuickFix>(GoDeleteRangeQuickFix(start, end, "Delete field '${element.identifier.text}'"))
                        } else arrayOf()
                    }
                    is GoFunctionDeclaration ->
                        arrayOf(DELETE_FUNCTION_FIX, GoRenameToBlankQuickFix(element))
                    is GoTypeSpec ->
                        arrayOf<LocalQuickFix>(DELETE_TYPE_FIX)
                    is GoVarDefinition ->
                        (if (GoInspectionUtil.canDeleteDefinition(element)) arrayOf(GoRenameToBlankQuickFix(element), GoDeleteVarDefinitionQuickFix(element.name))
                        else arrayOf<LocalQuickFix>(GoRenameToBlankQuickFix(element)))
                    is GoConstDefinition ->
                        (if (GoInspectionUtil.canDeleteDefinition(element)) arrayOf<LocalQuickFix>(GoDeleteConstDefinitionQuickFix(element.name)) else arrayOf())
//                    is GoMethodDeclaration -> arrayOf(GoDeleteQuickFix("Delete function", GoMethodDeclaration::class.java), GoRenameToBlankQuickFix(element))
//                    is GoLightMethodDeclaration -> arrayOf(GoDeleteQuickFix("Delete function", GoLightMethodDeclaration::class.java), GoRenameToBlankQuickFix(element))
                    else -> EmptyLocalQuickFix
                } to element.identifier?.textRange
            }
}