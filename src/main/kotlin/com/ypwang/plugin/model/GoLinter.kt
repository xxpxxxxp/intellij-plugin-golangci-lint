package com.ypwang.plugin.model

data class GoLinter(
    val defaultEnabled: Boolean,
    val name: String,
    val aka: String,
    val isDeprecated: Boolean,
    val description: String,
    val fast: Boolean,
    val autoFix: Boolean
) {
    val fullDescription by lazy {
        val dsBuilder = StringBuilder()
        if (isDeprecated)
            dsBuilder.append("[DEPRECATED] ")

        dsBuilder.append(description)
        dsBuilder.toString()
    }
}