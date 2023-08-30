package com.ypwang.plugin

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

class GoLinterSettingsState : BaseState() {
    var goLinterExe by string()
    var linterSelected by property(false)
    var enabledLinters by list<String>()
    var enableCustomProjectDir by property(true)
    // 2 state: empty, use project base path; no empty, use value
    var customProjectDir by string()
    var customConfigFile by string()
    var checkGoLinterExe by property(true)
    // don't use too much CPU. Runtime should have at least 1 available processor, right?
    var concurrency by property((Runtime.getRuntime().availableProcessors() + 3) / 4) { it == (Runtime.getRuntime().availableProcessors() + 3) / 4 }
    var severity by string()
}

@State(name = "GoLinterSettings", storages = [(Storage("golinter.xml"))])
class GoLinterSettings(internal val project: Project): SimplePersistentStateComponent<GoLinterSettingsState>(GoLinterSettingsState()) {
    companion object {
        @JvmStatic
        fun getInstance(project: Project): GoLinterSettings = project.service()
    }

    var goLinterExe
        get() = state.goLinterExe ?: ""
        set(value) { state.goLinterExe = value }

    var linterSelected
        get() = state.linterSelected
        set(value) { state.linterSelected = value }

    var enabledLinters
        get() = state.enabledLinters
        set(value) { state.enabledLinters = value }

    var enableCustomProjectDir
        get() = state.enableCustomProjectDir
        set(value) { state.enableCustomProjectDir = value }

    var customProjectDir
        get() = state.customProjectDir
        set(value) { state.customProjectDir = value }

    var customConfigFile
        get() = state.customConfigFile
        set(value) { state.customConfigFile = value }

    var checkGoLinterExe
        get() = state.checkGoLinterExe
        set(value) { state.checkGoLinterExe = value }

    var concurrency
        get() = state.concurrency
        set(value) { state.concurrency = value }

    var severity
        get() = state.severity
        set(value) { state.severity = value }

    override fun noStateLoaded() {
        super.noStateLoaded()
        loadState(GoLinterSettingsState())
    }
}