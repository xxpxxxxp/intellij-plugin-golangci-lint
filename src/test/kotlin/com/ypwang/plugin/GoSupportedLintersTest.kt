package com.ypwang.plugin

import org.junit.Assert
import org.junit.Test

class GoSupportedLintersTest {
    @Test
    fun linters() {
        val ls = GolangCiOutputParser.parseLinters(
                GolangCiOutputParser.runProcess(listOf("golangci-lint", "linters"), null, mapOf())
        )
        Assert.assertNotNull(ls.single { it.name == "golint" })
    }
}