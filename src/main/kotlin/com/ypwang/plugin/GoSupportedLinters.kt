package com.ypwang.plugin

import com.ypwang.plugin.model.GoLinter
import com.ypwang.plugin.util.Log
import com.ypwang.plugin.util.ProcessWrapper

class GoSupportedLinters private constructor(private val exec: String) {
    val Linters: List<GoLinter>

    init {
        val linters = mutableListOf<GoLinter>()
        try {
            val result = ProcessWrapper.fetchProcessOutput(ProcessBuilder(listOf(exec, "linters")).start())
            if (result.returnCode != 0)
                throw Exception("Execution failed")

            val linterRaw = result.stdout.lines()

            // format: name[ (aka)]: description [fast: bool, auto-fix: bool]
            val regex = Regex("""(?<name>\w+)( \((?<aka>[\w, ]+)\))?\: (?<description>.+) \[fast\: (?<fast>true|false), auto-fix\: (?<autofix>true|false)]""")
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
                            it.groups["aka"]?.value?:"",
                            it.groups["description"]!!.value,
                            it.groups["fast"]!!.value.toBoolean(),
                            it.groups["autofix"]!!.value.toBoolean())
                    )
                }
            }

        } catch (e: Exception) {
            Log.goLinter.error("Cannot get linters from $exec")
        }

        Linters = linters
    }

    companion object {
        private var goSupportedLinters: GoSupportedLinters? = null
        fun getInstance(exec: String): GoSupportedLinters {
            if (goSupportedLinters?.exec != exec) goSupportedLinters = GoSupportedLinters(exec)
            return goSupportedLinters!!
        }
    }
}