package com.ypwang.plugin

import org.junit.Assert
import org.junit.Test

class GoSupportedLintersTest {
    @Test
    fun linters() {
        val ls = GoSupportedLinters.getInstance("golangci-lint").linters
        Assert.assertNotNull(ls.single { it.name == "golint" })
    }
}