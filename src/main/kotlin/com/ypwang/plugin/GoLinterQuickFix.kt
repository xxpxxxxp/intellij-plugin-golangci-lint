package com.ypwang.plugin

import com.ypwang.plugin.handler.*

// attempt to suggest auto-fix, if possible, clarify affected PsiElement for better inspection
val quickFixHandler: Map<String, ProblemHandler> = mutableMapOf(
        "ineffassign" to IneffAssignHandler,
        "scopelint" to ScopelintHandler,
        "gocritic" to GoCriticHandler,
        "interfacer" to InterfacerHandler,
        "whitespace" to WhitespaceHandler,
        "golint" to GolintHandler,
        "goconst" to GoConstHandler,
        "maligned" to MalignedHandler,
        "unparam" to UnparamHandler,
        "dupl" to DuplHandler,
        "godot" to GoDotHandler,
        "testpackage" to TestPackageHandler,
        "goerr113" to GoErr113Handler,
        "stylecheck" to StyleCheckHandler,
        "gomnd" to GoMndHandler,
        "staticcheck" to StaticCheckHandler,
        "goprintffuncname" to GoPrintfFuncNameHandler,
//        "exhaustive" to ExhaustiveHandler,    // exhaustive is error prone
        "gosimple" to GoSimpleHandler,
        "gofumpt" to GoFumptHandler,
        "exportloopref" to ExportLoopRefHandler,
        "noctx" to NoCtxRefHandler,
        "makezero" to MarkZeroHandler,
        "thelper" to TestHelperHandler,
        "nlreturn" to NlReturnHandler,
        "errorlint" to ErrorLintHandler
).apply {
    this.putAll(listOf("structcheck", "varcheck", "deadcode", "unused").map { it to NamedElementHandler })
    this.putAll(ProblemHandler.FuncLinters.map { it to funcNoLintHandler(it) })
}