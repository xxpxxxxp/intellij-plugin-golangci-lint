package com.ypwang.plugin

import com.intellij.ide.util.PropertiesComponent
import org.jetbrains.annotations.NotNull
import java.util.*

object GoLinterConfig {
    private const val GO_LINTER_EXE = "golangci-lint"
    private const val GO_ENABLED_LINTERS = "go-enabled-linters"
    private const val GO_ENABLE_CUSTOM_DIR = "go-enable-custom-dir"
    private const val GO_CUSTOM_DIR = "go-custom-dir"
    private const val GO_CUSTOM_CONFIG = "go-custom-config"
    private const val CHECK_GO_LINTER_EXE = "check-golangci-lint"

    private val properties = PropertiesComponent.getInstance()

    var goLinterExe
        get() = properties.getValue(GO_LINTER_EXE, "")
        set(value) {
            properties.setValue(GO_LINTER_EXE, value)
        }

    var enabledLinters: Array<String>?
        get() = properties.getValues(GO_ENABLED_LINTERS)?.filter { it != null && it.isNotEmpty() }?.toTypedArray()
        set(@NotNull value) = properties.setValues(GO_ENABLED_LINTERS, value!!.filter { it.isNotEmpty() }.toTypedArray())

    var enableCustomProjectDir: Boolean
        get() = properties.getBoolean(GO_ENABLE_CUSTOM_DIR, true)
        set(value) = properties.setValue(GO_ENABLE_CUSTOM_DIR, value, true)

    // 2 state: empty, use project base path; no empty, use value
    var customProjectDir: Optional<String>
        get() = Optional.ofNullable(properties.getValue(GO_CUSTOM_DIR))
        set(value) = value.ifPresentOrElse({ properties.setValue(GO_CUSTOM_DIR, it) }, { properties.setValue(GO_CUSTOM_DIR, null) })

    var customConfigFile: Optional<String>
        get() = Optional.ofNullable(properties.getValue(GO_CUSTOM_CONFIG))
        set(value) = value.ifPresentOrElse({ properties.setValue(GO_CUSTOM_CONFIG, it) }, { properties.setValue(GO_CUSTOM_CONFIG, null) })

    var checkGoLinterExe: Boolean
        get() = properties.getBoolean(CHECK_GO_LINTER_EXE, true)
        set(value) = properties.setValue(CHECK_GO_LINTER_EXE, value, true)
}