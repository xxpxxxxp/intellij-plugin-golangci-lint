package com.ypwang.plugin.quickfix

import com.goide.codeInsight.imports.GoImport
import com.goide.codeInsight.imports.GoOptimizedImportsTracker
import com.goide.psi.GoCodeFragment
import com.goide.psi.GoFile
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class GoReplaceParameterTypeFix(
        private val packageName: String,
        private val element: PsiElement
) : LocalQuickFix {
    override fun getFamilyName(): String = "Replace with '${packageName.substring(packageName.lastIndexOf('/') + 1)}'"

    private fun alreadyImportedPackagesPaths(file: PsiFile?): Set<String> {
        return when (file) {
            is GoCodeFragment -> {
                val result = file.importedPackagesMap.keys
                val contextFile = file.contextFile
                if (contextFile != null) {
                    result.addAll(contextFile.importedPackagesMap.keys)
                }
                result
            }
            is GoFile -> file.importedPackagesMap.keys
            else -> setOf()
        }
    }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        // -1 means it's a bundled package
        val pre = packageName.lastIndexOf('/').let { if (it == -1) "" else packageName.substring(0, it + 1) }
        val shortName = packageName.substring(packageName.lastIndexOf('/') + 1)

        val moduleCut = shortName.indexOf('.')
        // -1 means in current package / internal package, no need to import
        if (moduleCut != -1) {
            val goImport = GoImport("$pre${shortName.substring(0, moduleCut)}")
            val file = element.containingFile
            CommandProcessor.getInstance().executeCommand(project, {
                ApplicationManager.getApplication().runWriteAction {
                    if (alreadyImportedPackagesPaths(file).contains(goImport.importPath)) {
                        GoOptimizedImportsTracker.removeOptimizedImport(file, goImport)
                    } else {
                        if ("C" == goImport.importPath) {
                            val importList = (file as GoFile).importList
                            if (importList != null) {
                                val importC = GoElementFactory.createImportDeclaration(project, "\"C\"")
                                importList.parent.addBefore(importC, importList)
                            }
                        } else {
                            (file as GoFile).addImport(goImport.importPath, goImport.alias)
                            GoOptimizedImportsTracker.removeOptimizedImport(file, goImport)
                        }
                    }
                }
            }, "Add import", null)
        }

        // JB got an weird issue if directly replace lastChild
        element.deleteChildRange(element.lastChild, element.lastChild)
        element.add(GoElementFactory.createExpression(project, shortName))
    }
}