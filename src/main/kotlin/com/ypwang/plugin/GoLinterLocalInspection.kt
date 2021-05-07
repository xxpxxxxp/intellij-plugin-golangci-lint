package com.ypwang.plugin

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import org.jetbrains.annotations.NonNls

class GoLinterLocalInspection : LocalInspectionTool(), ExternalAnnotatorBatchInspection {
    companion object {
        @NonNls
        const val SHORT_NAME = "GoLinter"
    }

    override fun getShortName(): String = SHORT_NAME
}