package com.ypwang.plugin.handler

import com.goide.inspections.GoInspectionUtil
import com.goide.psi.*
import com.goide.quickfix.*
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
                        arrayOf(GoDeleteQuickFix("Delete function ${element.identifier.text}", GoFunctionDeclaration::class.java), GoRenameToBlankQuickFix(element))
                    is GoTypeSpec ->
                        arrayOf<LocalQuickFix>(GoDeleteTypeQuickFix(element.identifier.text))
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