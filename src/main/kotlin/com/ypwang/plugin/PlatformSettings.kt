package com.ypwang.plugin

import com.intellij.openapi.util.SystemInfo

object PlatformSettings {
    val LinterExecutableName: String
    val GoExecutableName: String

    init {
        if (SystemInfo.isWindows) {
            LinterExecutableName = "golangci-lint.exe"
            GoExecutableName = "go.exe"
        } else {
            LinterExecutableName = "golangci-lint"
            GoExecutableName = "go"
        }
    }
}