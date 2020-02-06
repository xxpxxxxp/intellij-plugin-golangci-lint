package com.ypwang.plugin.model

data class GoLinter (
    val DefaultEnabled: Boolean,
    val Name: String,
    val Aka: String,
    val Description: String,
    val Fast: Boolean,
    val AutoFix: Boolean
) {
    val FullName by lazy {
        if (Aka.isEmpty()) Name else "$Name ($Aka)"
    }
}