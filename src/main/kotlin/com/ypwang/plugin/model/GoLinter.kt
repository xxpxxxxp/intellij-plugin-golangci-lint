package com.ypwang.plugin.model

data class GoLinter (
        val defaultEnabled: Boolean,
        val name: String,
        val aka: String,
        val isDeprecated: Boolean,
        val description: String,
        val fast: Boolean,
        val autoFix: Boolean
) {
    val fullName by lazy {
        if (aka.isEmpty()) name else "$name ($aka)"
    }
}