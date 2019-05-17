package com.ypwang.plugin

import com.intellij.openapi.util.SystemInfo

object PlatformSettings {
    val PathSpliter: String
    val LinterExecutableName: String
    val GoExecutableName: String

    init {
        if (SystemInfo.isWindows) {
            PathSpliter = ";"
            LinterExecutableName = "golangci-lint.exe"
            GoExecutableName = "go.exe"
        } else {
            PathSpliter = ":"
            LinterExecutableName = "golangci-lint"
            GoExecutableName = "go"
        }
    }
}