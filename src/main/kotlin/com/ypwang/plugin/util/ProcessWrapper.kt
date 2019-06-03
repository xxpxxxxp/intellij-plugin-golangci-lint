package com.ypwang.plugin.util

import com.intellij.openapi.diagnostic.Logger
import java.io.*

data class RunProcessResult(val returnCode: Int, val stdout: String, val stderr: String)

object ProcessWrapper {
    fun runWithArguments(arguments: List<String>, workingDir: String? = null): RunProcessResult {
        Log.golinter.info("Execute parameter: ${ arguments.joinToString(" ") }")
        val pb = ProcessBuilder(arguments)

        if (workingDir != null) pb.directory(File(workingDir))

        val process = pb.start()

        val outputConsumer = ByteArrayOutputStream()
        val outputThread = OutputReader.fetch(process.inputStream, outputConsumer)
        val errorConsumer = ByteArrayOutputStream()
        val errorThread = OutputReader.fetch(process.errorStream, errorConsumer)

        try {
            val returnCode = process.waitFor()
            errorThread.join()
            outputThread.join()

            return RunProcessResult(returnCode, outputConsumer.toString(), errorConsumer.toString())
        } catch (e: InterruptedException) {
            Logger.getInstance(this.javaClass).error(e)
        }

        return RunProcessResult(-1, "", "")
    }
}

private class OutputReader(val inputStream: InputStream, val consumer: ByteArrayOutputStream) : Runnable {
    override fun run() = try {
        val buf = ByteArray(1024)
        while (true) {
            val count = inputStream.read(buf)
            if (count == -1) break
            consumer.write(buf, 0, count)
        }
    } catch (e: IOException) {
        Logger.getInstance(this.javaClass).error(e)
    }

    companion object {
        fun fetch(inputStream: InputStream, consumer: ByteArrayOutputStream): Thread {
            val thread = Thread(OutputReader(inputStream, consumer))
            thread.start()
            return thread
        }
    }
}