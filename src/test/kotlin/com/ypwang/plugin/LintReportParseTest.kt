package com.ypwang.plugin

import com.google.gson.Gson
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.model.LintReport
import com.ypwang.plugin.model.Linter
import com.ypwang.plugin.model.Position
import org.junit.Assert
import org.junit.Test

class LintReportParseTest {
    @Test
    fun parseLintReport() {
        val reportJson = """{"Issues":[{"FromLinter":"structcheck","Text":"`member` is unused","Pos":{"Filename":"utils/zk_collaborator.go","Offset":238,"Line":20,"Column":2},"SourceLines":["\tmember map[string]string"],"Replacement":null},{"FromLinter":"structcheck","Text":"`target` is unused","Pos":{"Filename":"utils/zk_collaborator.go","Offset":264,"Line":21,"Column":2},"SourceLines":["\ttarget *ZkDistributedCollaborator"],"Replacement":null}],"Report":{"Linters":[{"Name":"govet","EnabledByDefault":true},{"Name":"errcheck","Enabled":true,"EnabledByDefault":true},{"Name":"golint","Enabled":true},{"Name":"gochecknoglobals"}]}}"""
        val report = Gson().fromJson(reportJson, LintReport::class.java)
        Assert.assertEquals(listOf(
            LintIssue("structcheck", "`member` is unused", Position("utils/zk_collaborator.go", 238, 20, 2), listOf("\tmember map[string]string"), null, null),
            LintIssue("structcheck", "`target` is unused", Position("utils/zk_collaborator.go", 264, 21, 2), listOf("\ttarget *ZkDistributedCollaborator"), null, null)
        ), report.Issues)
        Assert.assertEquals(listOf(
            Linter("govet", null, true),
            Linter("errcheck", true, true),
            Linter("golint", true, null),
            Linter("gochecknoglobals", null, null)
        ), report.Report.Linters)
    }
}