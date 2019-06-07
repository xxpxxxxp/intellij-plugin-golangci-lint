package com.ypwang.plugin.util

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import java.io.*
import java.util.concurrent.TimeUnit

data class RunProcessResult(val returnCode: Int, val stdout: String, val stderr: String)

@FunctionalInterface
interface ProcessInterruptAction {
    fun isCancelled(process: Process): Boolean
}

object ProcessWrapper {
    fun createProcessWithArguments(arguments: List<String>, workingDir: String? = null): Process {
        Log.goLinter.info("Execute parameter: ${ arguments.joinToString(" ") }")
        val pb = ProcessBuilder(arguments)
        if (workingDir != null) pb.directory(File(workingDir))
        return pb.start()
    }

    fun fetchProcessOutput(process: Process, timeoutInMillisec: Long = 0L, cancelAction: ProcessInterruptAction? = null): RunProcessResult {
        val outputConsumer = ByteArrayOutputStream()
        val outputThread = OutputReader.fetch(process.inputStream, outputConsumer)
        val errorConsumer = ByteArrayOutputStream()
        val errorThread = OutputReader.fetch(process.errorStream, errorConsumer)

        try {
            val returnCode =
                if (timeoutInMillisec <= 0L || cancelAction == null) process.waitFor()
                else {
                    (fun(): Int {
                        while (true) {
                            if (process.waitFor(timeoutInMillisec, TimeUnit.MILLISECONDS)) return process.exitValue()
                            if (cancelAction.isCancelled(process)) {
                                process.destroy()
                                throw ProcessCanceledException()
                            }
                        }
                    })()
                }

            errorThread.join()
            outputThread.join()

            return RunProcessResult(returnCode, outputConsumer.toString(), errorConsumer.toString())
        } catch (e: InterruptedException) {
            Logger.getInstance(this.javaClass).error(e)
        }

        return RunProcessResult(-1, "", "")
    }

    fun runWithArguments(arguments: List<String>, workingDir: String? = null): RunProcessResult
        = fetchProcessOutput(createProcessWithArguments(arguments, workingDir))
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