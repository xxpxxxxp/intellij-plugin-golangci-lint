package com.ypwang.plugin

import com.ypwang.plugin.util.Log
import com.ypwang.plugin.util.ProcessWrapper

class GoSupportedLinters private constructor(private val exec: String) {
    // (linter, description)
    val defaultEnabledLinters: List<Pair<String, String>>
    val defaultDisabledLinters: List<Pair<String, String>>

    init {
        val (enabled, disabled) =
            try {
                val result = ProcessWrapper.runWithArguments(listOf(exec, "linters"))
                if (result.returnCode != 0)
                    throw Exception("Execution failed")

                val linterRaw = result.stdout.lines()

                // parse output
                val enabledLinters = mutableListOf<Pair<String, String>>()
                val disabledLinters = mutableListOf<Pair<String, String>>()

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

                    val firstColon = line.indexOfFirst { it == ':' }
                    if (firstColon != -1) {
                        (if (enabled) enabledLinters else disabledLinters).add(line.substring(0, firstColon) to line.substring(firstColon + 1))

                    }
                }

                enabledLinters to disabledLinters
            } catch (e: Exception) {
                Log.golinter.error("Cannot get linters from $exec")
                listOf<Pair<String, String>>() to listOf<Pair<String, String>>()
            }

        defaultEnabledLinters = enabled
        defaultDisabledLinters = disabled
    }

    companion object {
        private var goSupportedLinters: GoSupportedLinters? = null
        fun getInstance(exec: String): GoSupportedLinters {
            if (goSupportedLinters?.exec != exec) goSupportedLinters = GoSupportedLinters(exec)
            return goSupportedLinters!!
        }
    }
}