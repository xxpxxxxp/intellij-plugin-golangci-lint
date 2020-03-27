package com.ypwang.plugin

import com.google.gson.Gson
import com.ypwang.plugin.model.GoLinter
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.model.LintReport
import com.ypwang.plugin.model.RunProcessResult
import java.io.File

object GolangCiOutputParser {
    fun runProcess(params: List<String>, runningDir: String?, env: Map<String, String>): RunProcessResult =
        fetchProcessOutput(ProcessBuilder(params).apply {
            val curEnv = this.environment()
            env.forEach { kv -> curEnv[kv.key] = kv.value }
            if (runningDir != null)
                this.directory(File(runningDir))
        }.start())

    fun parseIssues(result: RunProcessResult): List<LintIssue>? {
        assert(result.returnCode == 0 || result.returnCode == 1)
        return Gson().fromJson(
                // because first line will be the "--maligned.suggest-new" flag deprecation warning
                result.stdout.substring(result.stdout.indexOf('\n') + 1),
                LintReport::class.java).Issues?.let { it.sortedWith(compareBy({ issue -> issue.Pos.Filename }, { issue -> issue.Pos.Line })) }
    }

    fun parseLinters(result: RunProcessResult): List<GoLinter> {
        val linters = mutableListOf<GoLinter>()
        try {
            if (result.returnCode != 0)
                throw Exception("Execution failed")

            val linterRaw = result.stdout.lines()
            // format: name[ (aka)]: description [fast: bool, auto-fix: bool]
            val regex = Regex("""(?<name>\w+)( \((?<aka>[\w, ]+)\))?: (?<description>.+) \[fast: (?<fast>true|false), auto-fix: (?<autofix>true|false)]""")
            // parse output
            var enabled = true
            for (line in linterRaw) {
                if (line.isEmpty()) continue
                if (line.startsWith("Enabled")) {
                    enabled = true
                    continue
                }
                if (line.startsWith("Disabled")) {
                    enabled = false
                    continue
                }

                // use regex is a bit slow
                regex.matchEntire(line)?.let {
                    linters.add(
                            GoLinter(
                                    enabled,
                                    it.groups["name"]!!.value,
                                    it.groups["aka"]?.value ?: "",
                                    it.groups["description"]!!.value,
                                    it.groups["fast"]!!.value.toBoolean(),
                                    it.groups["autofix"]!!.value.toBoolean())
                    )
                }
            }

        } catch (e: Exception) {
            logger.error("Cannot get linters from running result: $result")
        }

        return linters
    }
}