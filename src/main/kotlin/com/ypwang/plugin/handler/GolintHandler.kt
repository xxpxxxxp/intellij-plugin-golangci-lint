package com.ypwang.plugin.handler

import com.goide.psi.*
import com.goide.quickfix.GoRenameToQuickFix
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.*

open class GolintHandler : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
        suggestFix(issue.Text.trim('"').filter { it != '`' }, file, document, issue, overrideLine)

    private val replaceRegex = Regex("""`?([\w\d_]+)`? should be `?([\w\d_]+)`?""")

    protected fun suggestFix(text: String, file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
        when {
            text == "if block ends with a return statement, so drop this else and outdent its block" ->
                chainFindAndHandle(file, document, issue, overrideLine) { element: GoElseStatement ->
                    arrayOf<LocalQuickFix>(GoOutdentElseFix(element)) to element.`else`.textRange
                }
            text == "if block ends with a return statement, so drop this else and outdent its block (move short variable declaration to its own line if necessary)" ->
                chainFindAndHandle(file, document, issue, overrideLine) { element: GoIfStatement ->
                    arrayOf<LocalQuickFix>(GoOutdentElseFix(element)) to element.`if`.textRange
                }
            text == "error should be the last type when returning multiple items" ->
                chainFindAndHandle(file, document, issue, overrideLine) { element: GoFunctionOrMethodDeclaration ->
                    arrayOf<LocalQuickFix>(GoReorderFuncReturnFix(element)) to element.signature!!.result!!.textRange
                }
            text == "context.Context should be the first parameter of a function" ->
                chainFindAndHandle(file, document, issue, overrideLine) { element: GoFunctionOrMethodDeclaration ->
                    arrayOf<LocalQuickFix>(GoReorderFuncParamFix(element)) to element.signature!!.parameters.textRange
                }
            text == "don't use ALL_CAPS in Go names; use CamelCase" || text.startsWith("don't use underscores in Go names;") ->
                chainFindAndHandle(file, document, issue, overrideLine) { element: GoNamedElement ->
                    // to CamelCase
                    val replace = element.identifier!!.text
                        .split('_')
                        .flatMap { it.withIndex().map { iv -> if (iv.index == 0) iv.value else iv.value.toLowerCase() } }
                        .joinToString("")

                    arrayOf<LocalQuickFix>(GoRenameToQuickFix(element, replace)) to element.identifier!!.textRange
                }
            text.startsWith("type name will be used as ") || text.startsWith("func name will be used as ") -> {
                val newName = text.substring(text.lastIndexOf(' ') + 1)
                chainFindAndHandle(file, document, issue, overrideLine) { element: GoNamedElement ->
                    if (element.identifier!!.text.startsWith(element.containingFile.packageName ?: "", true))
                        arrayOf<LocalQuickFix>(GoRenameToQuickFix(element, newName)) to element.identifier!!.textRange
                    else NonAvailableFix
                }
            }
            text.startsWith("don't use MixedCaps in package name") ->
                chainFindAndHandle(file, document, issue, overrideLine) { element: GoPackageClause ->
                    arrayOf<LocalQuickFix>(GoRenamePackageFix(element, element.identifier!!.text.toLowerCase())) to element.identifier!!.textRange
                }
            text.startsWith("should replace") -> {
                chainFindAndHandle(file, document, issue, overrideLine) { element: GoAssignmentStatement ->
                    val extracted = extractQuote(issue.Text, 2)
                    if (extracted.size == 2 && element.text == extracted[0]) {
                        extracted[1].let {
                            if (it.endsWith("++") || it.endsWith("--"))
                                arrayOf<LocalQuickFix>(GoReplaceElementFix(it, element, GoIncDecStatement::class.java)) to element.textRange
                            else
                                arrayOf<LocalQuickFix>(GoReplaceElementFix(it, element, GoAssignmentStatement::class.java)) to element.textRange
                        }
                    } else
                        NonAvailableFix
                }
            }
            text.startsWith("receiver name ") -> {
                val searchPattern = "receiver name "
                var begin = text.indexOf(searchPattern) + searchPattern.length
                val curName = text.substring(begin, text.indexOf(' ', begin))

                begin = text.indexOf(searchPattern, begin + 1) + searchPattern.length
                val newName = text.substring(begin, text.indexOf(' ', begin))
                chainFindAndHandle(file, document, issue, overrideLine) { element: GoMethodDeclaration ->
                    val receiver = element.receiver
                    if (receiver != null && receiver.identifier!!.text == curName) {
                        arrayOf<LocalQuickFix>(GoRenameToQuickFix(receiver, newName)) to receiver.identifier?.textRange
                    } else NonAvailableFix
                }
            }
            else -> {
                val match = replaceRegex.find(text)
                if (match != null)
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoNamedElement ->
                        if (element.identifier?.text == match.groups[1]!!.value)
                            arrayOf<LocalQuickFix>(GoRenameToQuickFix(element, match.groups[2]!!.value)) to element.identifier?.textRange
                        else NonAvailableFix
                    }
                else
                    NonAvailableFix
            }
        }
}