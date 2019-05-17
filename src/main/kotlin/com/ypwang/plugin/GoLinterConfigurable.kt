package com.ypwang.plugin

import com.intellij.openapi.options.SearchableConfigurable
import com.ypwang.plugin.form.GoLinterSettings
import javax.swing.JComponent

class GoLinterConfigurable: SearchableConfigurable {
    override fun getId(): String = "preference.GoLinterConfigurable"

    override fun getDisplayName(): String = "Go Linter"

    override fun getHelpTopic(): String? = "preference.GoLinterConfigurable"

    override fun apply() {
    }

    override fun createComponent(): JComponent? {
        return null
    }

    override fun isModified(): Boolean {
        return false
    }
}