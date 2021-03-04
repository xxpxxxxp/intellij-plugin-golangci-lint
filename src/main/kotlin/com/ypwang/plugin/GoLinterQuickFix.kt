package com.ypwang.plugin

import com.ypwang.plugin.handler.*

// attempt to suggest auto-fix, if possible, clarify affected PsiElement for better inspection
val quickFixHandler: Map<String, ProblemHandler> = mutableMapOf(
        "ineffassign" to IneffAssignHandler,
        "gocritic" to GoCriticHandler,
        "interfacer" to InterfacerHandler,
        "whitespace" to WhitespaceHandler,
        "golint" to GolintHandler(),
        "revive" to ReviveHandler,
        "goconst" to GoConstHandler,
        "unparam" to UnparamHandler,
        "dupl" to DuplHandler,
        "godot" to GoDotHandler,
        "testpackage" to TestPackageHandler,
        "stylecheck" to StyleCheckHandler,
        "gomnd" to GoMndHandler,
        "staticcheck" to StaticCheckHandler,
        "goprintffuncname" to GoPrintfFuncNameHandler,
//        "exhaustive" to ExhaustiveHandler,    // exhaustive is error prone
        "gosimple" to GoSimpleHandler,
        "gofumpt" to GoFumptHandler,
        "scopelint" to explanationHandler("https://github.com/xxpxxxxp/intellij-plugin-golangci-lint/blob/master/explanation/scopelint.md"),
        "goerr113" to explanationHandler("https://github.com/xxpxxxxp/intellij-plugin-golangci-lint/blob/master/explanation/goerr113.md"),
        "exportloopref" to explanationHandler("https://github.com/xxpxxxxp/intellij-plugin-golangci-lint/blob/master/explanation/exportloopref.md"),
        "noctx" to explanationHandler("https://github.com/sonatard/noctx/blob/master/README.md"),
        "makezero" to explanationHandler("https://github.com/ashanbrown/makezero#purpose"),
        "thelper" to explanationHandler("https://github.com/kulti/thelper#why"),
        "durationcheck" to explanationHandler("https://github.com/charithe/durationcheck#duration-check"),
        "nlreturn" to NlReturnHandler,
        "errorlint" to ErrorLintHandler,
        "ifshort" to IfShortHandler,
        "forcetypeassert" to ForceTypeAssertHandler,
        "predeclared" to PreDeclaredHandler
).apply {
    this.putAll(listOf("structcheck", "varcheck", "deadcode", "unused").map { it to NamedElementHandler })
    this.putAll(ProblemHandler.FuncLinters.map { it to funcNoLintHandler(it) })
}