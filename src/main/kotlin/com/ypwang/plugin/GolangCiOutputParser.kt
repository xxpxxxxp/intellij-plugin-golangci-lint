package com.ypwang.plugin

import com.google.gson.Gson
import com.intellij.openapi.project.Project
import com.ypwang.plugin.model.GoLinter
import com.ypwang.plugin.model.LintIssue
import com.ypwang.plugin.model.LintReport
import com.ypwang.plugin.model.RunProcessResult
import java.io.File
import java.nio.charset.Charset
import java.util.*

object GolangCiOutputParser {
    fun runProcess(params: List<String>, runningDir: String?, env: Map<String, String>, encoding: Charset = Charset.defaultCharset()): RunProcessResult =
        fetchProcessOutput(
            ProcessBuilder(params).apply {
                val curEnv = this.environment()
                env.forEach { kv -> curEnv[kv.key] = kv.value }
                if (runningDir != null)
                    this.directory(File(runningDir))
            }.start(),
            encoding
        )

    fun parseIssues(result: RunProcessResult): List<LintIssue> {
        assert(result.returnCode == 0 || result.returnCode == 1)
        return Gson().fromJson(result.stdout, LintReport::class.java)
                .Issues
                ?.sortedWith(compareBy({ issue -> issue.Pos.Filename }, { issue -> issue.Pos.Line }))
                ?: Collections.emptyList()
    }

    fun parseLinters(project: Project, result: RunProcessResult): List<GoLinter> {
        when (result.returnCode) {
            0 -> {
                // continue
            }
            2 ->
                if (isGo18(project))
                    throw Exception("Incompatible golangci-lint with Go1.18, please update to version after 1.45.0")
                else
                    throw Exception("golangci-lint panic: $result")
            else ->
                throw Exception("Failed to Discover Linters: $result")
        }

        val linters = mutableListOf<GoLinter>()
        val linterRaw = result.stdout.lines()
        // format: name[ (aka)][ deprecated]: description [fast: bool, auto-fix: bool]
        val regex = Regex("""(?<name>\w+)( \((?<aka>[\w, ]+)\))?( \[(?<deprecated>deprecated)])?: (?<description>.+) \[fast: (?<fast>true|false), auto-fix: (?<autofix>true|false)]""")
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
                        it.groups["deprecated"]?.value == "deprecated",
                        it.groups["description"]!!.value,
                        it.groups["fast"]!!.value.toBoolean(),
                        it.groups["autofix"]!!.value.toBoolean(),
                    )
                )
            }
        }

        return linters
    }
}