package com.ypwang.plugin

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.GlobalInspectionTool
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptionsProcessor

class GoLinterGlobalInspection: GlobalInspectionTool() {
    override fun runInspection(
            scope: AnalysisScope,
            manager: InspectionManager,
            globalContext: GlobalInspectionContext,
            problemDescriptionsProcessor: ProblemDescriptionsProcessor
    ) {
        super.runInspection(scope, manager, globalContext, problemDescriptionsProcessor)
    }
}