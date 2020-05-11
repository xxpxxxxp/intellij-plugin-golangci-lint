package com.ypwang.plugin

import com.goide.inspections.GoInspectionUtil
import com.goide.psi.*
import com.goide.psi.impl.GoLiteralImpl
import com.goide.quickfix.*
import com.ypwang.plugin.quickfix.GoReorderFuncReturnFix
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.PsiCommentImpl
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.quickfix.*

private val emptyLocalQuickFix = arrayOf<LocalQuickFix>()
private val nonAvailableFix = emptyLocalQuickFix to null

private fun calcPos(document: Document, issue: LintIssue, overrideLine: Int): Int =
        // some linter reports whole line
        if (issue.Pos.Column == 0) document.getLineStartOffset(overrideLine)
        // Column is 1-based
        else document.getLineStartOffset(overrideLine) + issue.Pos.Column - 1

private inline fun <reified T : PsiElement> chainFindAndHandle(
        file: PsiFile,
        document: Document,
        issue: LintIssue,
        overrideLine: Int,
        handler: (T) -> Pair<Array<LocalQuickFix>, TextRange?>
): Pair<Array<LocalQuickFix>, TextRange?> {
    var element = file.findElementAt(calcPos(document, issue, overrideLine))
    while (element != null) {
        if (element is T)
            return handler(element)
        element = element.parent
    }
    return nonAvailableFix
}

// they reports issue of whole function
private val funcLinters = setOf("funlen", "gocognit", "gochecknoinits", "gocyclo", "nakedret")

abstract class ProblemHandler {
    fun suggestFix(linter: String, file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange> {
        val fix = try {
            var (_fix, range) = doSuggestFix(file, document, issue, overrideLine)

            // generally, if there's quick fix available, we won't suggest nolint
            // and func linters will be take care separately
            if (_fix.isEmpty() && linter !in funcLinters)
                _fix = arrayOf(NoLintSingleLineCommentFix(linter))

            if (range != null)
                return _fix to range

            _fix
        } catch (e: Exception) {
            // ignore
            emptyLocalQuickFix
        }

        val pos = calcPos(document, issue, overrideLine)
        return fix to TextRange.create(pos, maxOf(document.getLineEndOffset(overrideLine), pos))
    }

    open fun description(issue: LintIssue): String = "${issue.Text} (${issue.FromLinter})"
    abstract fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?>
}

val defaultHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> = nonAvailableFix
}

private val namedElementHandler = object : ProblemHandler() {
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
                    else -> emptyLocalQuickFix
                } to element.identifier?.textRange
            }
}

private val ineffassignHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, document, issue, overrideLine) { element: GoReferenceExpression ->
                // get the variable
                val variable = issue.Text.substring(issue.Text.lastIndexOf(' ') + 1).trim('`')
                // normally cur pos is LeafPsiElement, parent should be GoVarDefinition (a := 1) or GoReferenceExpressImpl (a = 1)
                // we cannot delete/rename GoVarDefinition, as that would have surprising impact on usage below
                // while for Reference we could safely rename it to '_' without causing damage
                if (element.text == variable)
                    arrayOf<LocalQuickFix>(GoReferenceRenameToBlankQuickFix(element)) to element.identifier.textRange
                else nonAvailableFix
            }
}

private val scopelintHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            arrayOf<LocalQuickFix>(GoBringToExplanationFix("https://github.com/xxpxxxxp/intellij-plugin-golangci-lint/blob/master/explanation/scopelint.md")) to null
}

private val interfacerHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, document, issue, overrideLine) { element: GoParameterDeclaration ->
                // last child is type signature
                arrayOf<LocalQuickFix>(GoReplaceParameterTypeFix(
                        issue.Text.substring(issue.Text.lastIndexOf(' ') + 1).trim('`'),
                        element
                )) to element.lastChild.textRange
            }
}

private val gocriticHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            when {
                issue.Text.startsWith("assignOp: replace") -> {
                    var begin = issue.Text.indexOf('`')
                    var end = issue.Text.indexOf('`', begin + 1)
                    val currentAssignment = issue.Text.substring(begin + 1, end)

                    begin = issue.Text.indexOf('`', end + 1)
                    end = issue.Text.indexOf('`', begin + 1)
                    val replace = issue.Text.substring(begin + 1, end)

                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoAssignmentStatement ->
                        if (element.text == currentAssignment) {
                            if (replace.endsWith("++") || replace.endsWith("--"))
                                arrayOf<LocalQuickFix>(GoReplaceElementFix(replace, element, GoIncDecStatement::class.java)) to element.textRange
                            else
                                arrayOf<LocalQuickFix>(GoReplaceElementFix(replace, element, GoAssignmentStatement::class.java)) to element.textRange
                        } else nonAvailableFix
                    }
                }
                issue.Text.startsWith("sloppyLen:") ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoConditionalExpr ->
                        if (issue.Text.contains(element.text)) {
                            val searchPattern = "can be "
                            val replace = issue.Text.substring(issue.Text.indexOf(searchPattern) + searchPattern.length)
                            arrayOf<LocalQuickFix>(GoReplaceElementFix(replace, element, GoConditionalExpr::class.java)) to element.textRange
                        } else nonAvailableFix
                    }
                issue.Text.startsWith("unslice:") ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoIndexOrSliceExpr ->
                        if (issue.Text.contains(element.text) && element.expression != null)
                            arrayOf<LocalQuickFix>(GoReplaceExpressionFix(element.expression!!.text, element)) to element.textRange
                        else nonAvailableFix
                    }
                issue.Text.startsWith("captLocal:") ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoParamDefinition ->
                        val text = element.identifier.text
                        if (text[0].isUpperCase())
                            arrayOf<LocalQuickFix>(GoRenameToQuickFix(element, text[0].toLowerCase() + text.substring(1))) to element.identifier.textRange
                        else nonAvailableFix
                    }
                issue.Text == "elseif: can replace 'else {if cond {}}' with 'else if cond {}'" -> {
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoElseStatement ->
                        if (element.block?.statementList?.size == 1)
                            arrayOf<LocalQuickFix>(GoOutdentInnerIfFix(element)) to element.textRange
                        else nonAvailableFix
                    }
                }
//                issue.Text == "singleCaseSwitch: should rewrite switch statement to if statement" ->
//                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoSwitchStatement ->
//                        // cannot handle typed-switch statement because that introduce new variable
//                        (if (element is GoExprSwitchStatement) arrayOf<LocalQuickFix>(GoSingleCaseSwitchFix(element))
//                        else emptyLocalQuickFix) to element.textRange
//                    }
                else -> nonAvailableFix
            }
}

private val golintHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            when {
                issue.Text.startsWith("var ") || issue.Text.startsWith("const ") -> {
                    var begin = issue.Text.indexOf('`')
                    var end = issue.Text.indexOf('`', begin + 1)
                    val curName = issue.Text.substring(begin + 1, end)

                    begin = issue.Text.indexOf('`', end + 1)
                    end = issue.Text.indexOf('`', begin + 1)
                    val newName = issue.Text.substring(begin + 1, end)

                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoNamedElement ->
                        if (element.text == curName)
                            arrayOf<LocalQuickFix>(GoRenameToQuickFix(element, newName)) to element.identifier?.textRange
                        else nonAvailableFix
                    }
                }
                issue.Text.startsWith("func ") -> {
                    val match = Regex("""func ([\w\d_]+) should be ([\w\d_]+)""").matchEntire(issue.Text)
                    if (match != null) {
                        chainFindAndHandle(file, document, issue, overrideLine) { element: GoFunctionDeclaration ->
                            if (element.identifier.text == match.groups[1]!!.value)
                                arrayOf<LocalQuickFix>(GoRenameToQuickFix(element, match.groups[2]!!.value)) to element.identifier.textRange
                            else nonAvailableFix
                        }
                    } else nonAvailableFix
                }
                issue.Text.startsWith("receiver name ") -> {
                    val searchPattern = "receiver name "
                    var begin = issue.Text.indexOf(searchPattern) + searchPattern.length
                    val curName = issue.Text.substring(begin, issue.Text.indexOf(' ', begin))

                    begin = issue.Text.indexOf(searchPattern, begin + 1) + searchPattern.length
                    val newName = issue.Text.substring(begin, issue.Text.indexOf(' ', begin))
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoMethodDeclaration ->
                        val receiver = element.receiver
                        if (receiver != null && receiver.identifier!!.text == curName) {
                            arrayOf<LocalQuickFix>(GoRenameToQuickFix(receiver, newName)) to receiver.identifier?.textRange
                        } else nonAvailableFix
                    }
                }
                issue.Text.startsWith("type name will be used as ") -> {
                    val newName = issue.Text.substring(issue.Text.lastIndexOf(' ') + 1)
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoTypeSpec ->
                        if (element.identifier.text.startsWith(element.containingFile.packageName ?: "", true))
                            arrayOf<LocalQuickFix>(GoRenameToQuickFix(element, newName)) to element.identifier.textRange
                        else nonAvailableFix
                    }
                }
                issue.Text == "don't use ALL_CAPS in Go names; use CamelCase" || issue.Text.startsWith("don't use underscores in Go names;") ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoNamedElement ->
                        // to CamelCase
                        val replace = element.identifier!!.text
                                .split('_')
                                .flatMap { it.withIndex().map { iv -> if (iv.index == 0) iv.value else iv.value.toLowerCase() } }
                                .joinToString("")

                        arrayOf<LocalQuickFix>(GoRenameToQuickFix(element, replace)) to element.identifier!!.textRange
                    }
                issue.Text == "`if` block ends with a `return` statement, so drop this `else` and outdent its block" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoElseStatement ->
                        arrayOf<LocalQuickFix>(GoOutdentElseFix(element)) to element.textRange
                    }
                issue.Text == "error should be the last type when returning multiple items" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoFunctionOrMethodDeclaration ->
                        arrayOf<LocalQuickFix>(GoReorderFuncReturnFix(element)) to element.signature!!.result!!.textRange
                    }
                else -> nonAvailableFix
            }
}

private val whitespaceHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> {
        assert(issue.LineRange != null)

        val elements = mutableListOf<PsiElement>()
        val shift = overrideLine - issue.Pos.Line
        // whitespace linter tells us the start line and end line
        for (l in issue.LineRange!!.To downTo issue.LineRange.From) {
            val line = l + shift
            // line in document starts from 0
            val s = document.getLineStartOffset(line)
            val e = document.getLineEndOffset(line)
            if (s == e) {
                // whitespace line
                val element = file.findElementAt(s)
                if (element is PsiWhiteSpaceImpl && element.chars.all { it == '\n' })
                    elements.add(element)
            }
        }

        val start = document.getLineStartOffset(issue.LineRange.From + shift)
        val end = document.getLineEndOffset(issue.LineRange.To + shift)
        if (elements.isNotEmpty()) return arrayOf<LocalQuickFix>(GoDeleteWhiteSpaceFix(elements)) to TextRange(start, end)

        return nonAvailableFix
    }
}

private val unparamHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            // intellij will report same issue and provides fix, just fit the range
            chainFindAndHandle(file, document, issue, overrideLine) { element: GoParameterDeclaration ->
                var psiElement: PsiElement? = element
                while (psiElement != null && psiElement !is GoFunctionOrMethodDeclaration)
                    psiElement = psiElement.parent

                val fix = if (psiElement is GoFunctionOrMethodDeclaration) arrayOf<LocalQuickFix>(NoLintFuncCommentFix("unparam", psiElement)) else emptyLocalQuickFix
                fix to element.textRange
            }
}

private val duplHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> {
        assert(issue.LineRange != null)
        // to avoid annoying, just show the first line
        val start = document.getLineStartOffset(overrideLine)
        val end = document.getLineEndOffset(overrideLine)
        return emptyLocalQuickFix to TextRange(start, end)
    }
}

private val goconstHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, document, issue, overrideLine) { element: GoStringLiteral ->
                arrayOf<LocalQuickFix>(GoIntroduceConstStringLiteralFix(file as GoFile, element.text)) to element.textRange
            }
}

private val malignedHandler = object : ProblemHandler() {
    override fun description(issue: LintIssue): String {
        val lineBreak = issue.Text.indexOf(":\n")
        if (lineBreak != -1)
            return issue.Text.substring(0, lineBreak) + " (maligned)"

        return super.description(issue)
    }

    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> {
        val lineBreak = issue.Text.indexOf(":\n")
        if (lineBreak != -1) {
            return chainFindAndHandle(file, document, issue, overrideLine) { element: GoTypeDeclaration ->
                arrayOf<LocalQuickFix>(
                        GoReorderStructFieldFix(
                                element.typeSpecList.first().identifier.text,
                                issue.Text.substring(lineBreak + 2).trim('`'),
                                element
                        )
                ) to element.typeSpecList.first().identifier.textRange
            }
        }

        return nonAvailableFix
    }
}

private val godotHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, document, issue, overrideLine) { element: PsiCommentImpl ->
                arrayOf<LocalQuickFix>(GoDotFix(element)) to element.textRange
            }
}

private val testpackageHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, document, issue, overrideLine) { element: GoPackageClause ->
                arrayOf<LocalQuickFix>(GoRenamePackageFix(element, element.identifier!!.text + "_test")) to element.identifier!!.textRange
            }
}

private val goerr113Handler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            arrayOf<LocalQuickFix>(GoBringToExplanationFix("https://github.com/xxpxxxxp/intellij-plugin-golangci-lint/blob/master/explanation/goerr113.md")) to null
}

private val stylecheckHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            when (issue.Text.substring(0, issue.Text.indexOf(':'))) {
                // don't use Yoda conditions
                "ST1017" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoConditionalExpr ->
                        if (element.left is GoLiteral) arrayOf<LocalQuickFix>(GoSwapBinaryExprFix(element)) to element.textRange
                        else nonAvailableFix
                    }
                // escape invisible character
                "ST1018" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoStringLiteral ->
                        val begin = issue.Text.indexOf('\'')
                        val end = issue.Text.indexOf('\'', begin + 1)
                        val utfChar = issue.Text.substring(begin + 1, end)
                        assert(utfChar.startsWith("\\u"))
                        arrayOf<LocalQuickFix>(GoReplaceInvisibleCharInStringFix(element, utfChar.substring(2).toInt(16))) to element.textRange
                    }
                else -> nonAvailableFix
            }
}

private val gomndHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, document, issue, overrideLine) { element: GoLiteralImpl ->
                emptyLocalQuickFix to element.textRange
            }
}

private val staticcheckHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            when (issue.Text.substring(0, issue.Text.indexOf(':'))) {
                "SA9003" ->
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoStatement ->
                        arrayOf<LocalQuickFix>(GoDeleteElementFix(element, "statement")) to element.textRange
                    }
                else -> nonAvailableFix
            }
}

private val goprintffuncnameHandler = object : ProblemHandler() {
    override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
            chainFindAndHandle(file, document, issue, overrideLine) { element: GoFunctionOrMethodDeclaration ->
                arrayOf<LocalQuickFix>(GoRenameToQuickFix(element, "${element.identifier!!.text}f")) to element.identifier!!.textRange
            }
}

private fun funcNoLintHandler(linter: String): ProblemHandler =
        object : ProblemHandler() {
            override fun doSuggestFix(file: PsiFile, document: Document, issue: LintIssue, overrideLine: Int): Pair<Array<LocalQuickFix>, TextRange?> =
                    chainFindAndHandle(file, document, issue, overrideLine) { element: GoFunctionOrMethodDeclaration ->
                        arrayOf<LocalQuickFix>(NoLintFuncCommentFix(linter, element)) to null
                    }
        }

// attempt to suggest auto-fix, if possible, clarify affected PsiElement for better inspection
val quickFixHandler: Map<String, ProblemHandler> = mutableMapOf(
        "ineffassign" to ineffassignHandler,
        "scopelint" to scopelintHandler,
        "gocritic" to gocriticHandler,
        "interfacer" to interfacerHandler,
        "whitespace" to whitespaceHandler,
        "golint" to golintHandler,
        "goconst" to goconstHandler,
        "maligned" to malignedHandler,
        "unparam" to unparamHandler,
        "dupl" to duplHandler,
        "godot" to godotHandler,
        "testpackage" to testpackageHandler,
        "goerr113" to goerr113Handler,
        "stylecheck" to stylecheckHandler,
        "gomnd" to gomndHandler,
        "staticcheck" to staticcheckHandler,
        "goprintffuncname" to goprintffuncnameHandler
    ).apply {
        this.putAll(listOf("structcheck", "varcheck", "deadcode", "unused").map { it to namedElementHandler })
        this.putAll(funcLinters.map { it to funcNoLintHandler(it) })
    }