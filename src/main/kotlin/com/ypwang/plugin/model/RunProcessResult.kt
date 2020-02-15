package com.ypwang.plugin.model

data class RunProcessResult(
        val returnCode: Int,
        val stdout: String,
        val stderr: String
)