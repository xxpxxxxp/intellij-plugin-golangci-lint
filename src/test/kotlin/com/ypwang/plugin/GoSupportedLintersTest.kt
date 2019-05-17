package com.ypwang.plugin

import org.junit.Assert
import org.junit.Test

class GoSupportedLintersTest {
    @Test
    fun linters() {
        val goSupportedLinters = GoSupportedLinters.getInstance("golangci-lint")
        println(goSupportedLinters.defaultEnabledLinters)
        println(goSupportedLinters.defaultDisabledLinters)
        Assert.assertTrue("golint" in goSupportedLinters.defaultDisabledLinters)
    }
}