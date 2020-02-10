package com.ypwang.plugin

import com.intellij.ide.util.PropertiesComponent

object GoLinterConfig {
    private const val GO_LINTER_EXE = "golangci-lint"
    private const val GO_ENABLED_LINTERS = "go-enabled-linters"
    private const val GO_USE_CUSTOM_OPTIONS = "go-use-custom-options"
    private const val GO_CUSTOM_OPTIONS = "go-custom-options"
    private const val GO_USE_CONFIG_FILE = "go-use-config-file"

    private val properties = PropertiesComponent.getInstance()

    var goLinterExe
        get() = properties.getValue(GO_LINTER_EXE, "")
        set(value) {
            properties.setValue(GO_LINTER_EXE, value)
        }

    var enabledLinters: Array<String>?
        get() = properties.getValues(GO_ENABLED_LINTERS)
        set(value) = properties.setValues(GO_ENABLED_LINTERS, value)

    var useCustomOptions: Boolean
        get() = properties.getBoolean(GO_USE_CUSTOM_OPTIONS, false)
        set(value) = properties.setValue(GO_USE_CUSTOM_OPTIONS, value)

    var customOptions: String
        get() = properties.getValue(GO_CUSTOM_OPTIONS, "")
        set(value) = properties.setValue(GO_CUSTOM_OPTIONS, value)

    var useConfigFile: Boolean
        get() = properties.getBoolean(GO_USE_CONFIG_FILE, false)
        set(value) = properties.setValue(GO_USE_CONFIG_FILE, value)
}