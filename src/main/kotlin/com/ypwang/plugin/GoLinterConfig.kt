package com.ypwang.plugin

import com.intellij.ide.util.PropertiesComponent
import org.jetbrains.annotations.NotNull
import java.util.Optional

object GoLinterConfig {
    private const val GO_LINTER_EXE = "golangci-lint"
    private const val GO_ENABLED_LINTERS = "go-enabled-linters"
    private const val GO_CUSTOM_DIR = "go-custom-dir"
    private const val CHECK_GO_LINTER_EXE = "check-golangci-lint"

    private val properties = PropertiesComponent.getInstance()

    var goLinterExe
        get() = properties.getValue(GO_LINTER_EXE, "")
        set(value) {
            properties.setValue(GO_LINTER_EXE, value)
        }

    var enabledLinters: Array<String>?
        get() = properties.getValues(GO_ENABLED_LINTERS)?.filter { it != null && it.isNotEmpty() }?.toTypedArray() as Array<String>?
        set(@NotNull value) = properties.setValues(GO_ENABLED_LINTERS, value!!.filter { it.isNotEmpty() }.toTypedArray())

    // 2 state: empty, use project base path; no empty, use value
    var customProjectDir: Optional<String>
        get() = Optional.ofNullable(properties.getValue(GO_CUSTOM_DIR))
        set(value) = value.ifPresent { properties.setValue(GO_CUSTOM_DIR, it) }

    var checkGoLinterExe: Boolean
        get() = properties.getBoolean(CHECK_GO_LINTER_EXE, true)
        set(value) = properties.setValue(CHECK_GO_LINTER_EXE, value)
}