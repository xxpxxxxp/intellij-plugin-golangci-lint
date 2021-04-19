package com.ypwang.plugin.quickfix

import com.goide.psi.*
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.diagnostic.AttachmentFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace

class GoDeleteVarDefinitionFix(element: GoVarDefinition)
    : LocalQuickFixAndIntentionActionOnPsiElement(element) {
    override fun getFamilyName(): String = text
    override fun getText(): String = "Delete variable '${(startElement as GoVarDefinition).name}'"

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement
    ) {
        val element = startElement as GoVarDefinition
        if (element.isValid) {
            GoReferencesSearch.search(element, element.useScope).forEach { reference ->
                val usage = reference.element
                val parent = usage.parent
                if (usage is GoVarDefinition) {
                    (parent as GoVarSpec).deleteDefinition(usage)
                } else {
                    if (usage is GoReferenceExpression) {
                        if (parent is GoLeftHandExprList) {
                            val assignment = parent.getParent() as? GoAssignmentStatement
                            if (assignment != null && assignment.deleteExpression(usage))
                                return
                        }

                        reference.handleElementRename("_")
                    }

                }
            }
            val parent = element.parent
            if (parent is GoVarSpec) {
                parent.deleteDefinition(element)
            } else {
                if (parent is GoTypeSwitchGuard) {
                    val varAssign = parent.varAssign
                    if (varAssign != null) {
                        var lastElement: PsiElement = varAssign
                        while (lastElement.nextSibling is PsiWhiteSpace) {
                            lastElement = lastElement.nextSibling
                        }

                        parent.deleteChildRange(element, lastElement)
                        return
                    }
                }

                LOG.error(
                    "Cannot delete variable " + element.name + ". Parent: " + parent::class.java.simpleName,
                    AttachmentFactory.createAttachment(parent.containingFile.virtualFile)
                )
            }
        }
    }
}