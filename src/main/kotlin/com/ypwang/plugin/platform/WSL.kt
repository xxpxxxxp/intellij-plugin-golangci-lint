package com.ypwang.plugin.platform

class WSL(goRoot: String): Platform {
    override fun buildCommand(params: List<String>, runningDir: String?, env: Map<String, String>): String {
        TODO("Not yet implemented")
    }

    override fun os(): String {
        TODO("Not yet implemented")
    }

    override fun suffix(): String {
        TODO("Not yet implemented")
    }

    override fun linterName(): String {
        TODO("Not yet implemented")
    }

    override fun tempPath(): String {
        TODO("Not yet implemented")
    }

    override fun defaultPath(): String {
        TODO("Not yet implemented")
    }

    override fun decompress(
        compressed: String,
        targetFile: String,
        to: String,
        setFraction: (Double) -> Unit,
        cancelled: () -> Boolean
    ) {
        TODO("Not yet implemented")
    }

}
