package com.ypwang.plugin

import org.junit.Assert
import org.junit.Test

class GoSupportedLintersTest {
    @Test
    fun linters() {
        val goSupportedLinters = GoSupportedLinters.getInstance("golangci-lint")
        println(goSupportedLinters.defaultEnabledLinters)
        println(goSupportedLinters.defaultDisabledLinters)
        Assert.assertTrue(
                "golint" to "Golint differs from gofmt. Gofmt reformats Go source code, whereas golint prints out style mistakes [fast: true, auto-fix: false]"
                        in goSupportedLinters.defaultDisabledLinters)
    }
}