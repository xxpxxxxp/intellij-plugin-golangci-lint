package com.ypwang.plugin.quickfix

import com.goide.psi.GoFieldDeclaration
import com.goide.psi.GoFile
import com.goide.psi.GoStructType
import com.goide.psi.GoTypeDeclaration
import com.goide.psi.impl.GoElementFactory
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project

class GoReorderStructFieldFix(
        private val structName: String,
        private val replacement: String,
        private val element: GoTypeDeclaration
) : LocalQuickFix {
    override fun getFamilyName(): String = "Reorder struct '$structName' (caution: comments will be left out & double check result!)"

    // reorder field is tricky, because we got limited info from maligned linter
    // and struct might have comment/tag/multi field on same line
    // so strict limitations is applied, we would rather do nothing than break the code
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        try {
            val originTypeDefinition = element.typeSpecList.single().specType.type as GoStructType
            val originFieldDeclaration = originTypeDefinition.fieldDeclarationList
            val originAnonymousFieldDeclaration = originFieldDeclaration.filter { it.anonymousFieldDefinition != null }
                    .map { it.anonymousFieldDefinition!!.text to it.tag }
                    .toMap()
            val originalFieldMap = originFieldDeclaration.flatMap { it.fieldDefinitionList.map { d -> d.identifier.text to Pair(it.type, it.tag) } }.toMap()

            val importModuleToAlias = (element.containingFile as GoFile).importList?.importDeclarationList
                    ?.flatMap {it.importSpecList }
                    ?.map { it.path to it.alias }
                    ?.toMap() ?: mapOf()

            val replaceFields = mutableListOf<GoFieldDeclaration>()
            for (fieldLine in replacement.split('\n').map { it.trim('\t', '\n', ',') }.filter { it.isNotEmpty() && it != "struct{" && it != "}" }) {
                val segment = fieldLine.split(' ', '\t').filter { it.isNotEmpty() }
                if (segment.isEmpty()) return
                val field = segment[0]

                val replace =
                        if (field in originalFieldMap) {
                            val decl = originalFieldMap[field]!!
                            GoElementFactory.createElement(project, "package a; \n type b struct {\n $field\t${decl.first!!.text}\t${decl.second?.text?:""}\n}", GoFieldDeclaration::class.java)
                                    ?: return       // something must be wrong, can not perform change
                        } else {
                            // anonymous field, shorten the import
                            if (segment.size != 2) return
                            val fieldType = segment[1]
                            val isPointer = fieldType.startsWith('*')
                            val fullType = fieldType.trimStart('*')

                            val pathBreak = fullType.lastIndexOf('/')

                            var relativeType = if (pathBreak == -1)
                                // must be internal type usage, keep the same
                                fullType
                            else {
                                val shortType = fullType.substring(pathBreak + 1)
                                val moduleBreak = shortType.indexOf('.')

                                if (moduleBreak == -1) return
                                val module = shortType.substring(0, moduleBreak)
                                val rawType = shortType.substring(moduleBreak + 1)
                                val modulePath = "${fullType.substring(0, pathBreak)}/$module"

                                if (modulePath in importModuleToAlias)
                                    "${importModuleToAlias[modulePath] ?: module}.$rawType"
                                else
                                // for those not found, it's either imported by '_', or in current package
                                    rawType
                            }

                            if (isPointer) relativeType = "*$relativeType"
                            if (relativeType !in originAnonymousFieldDeclaration) return
                            GoElementFactory.createElement(
                                    project,
                                    "package a; \n type b struct {\n $relativeType\t${originAnonymousFieldDeclaration[relativeType]?.text ?: ""}\n}",
                                    GoFieldDeclaration::class.java)
                                    ?: return       // something must be wrong, can not perform change
                        }
                replaceFields.add(replace)
            }

            if (originAnonymousFieldDeclaration.size + originalFieldMap.size == replaceFields.size) {
                // remove only field declaration, leave comments
                originFieldDeclaration.forEach { it.delete() }
                // add replacements
                replaceFields.reversed().forEach { originTypeDefinition.addAfter(it, originTypeDefinition.lbrace) }
            }
        } catch (_: Exception) {
            // ignore
        }
    }
}