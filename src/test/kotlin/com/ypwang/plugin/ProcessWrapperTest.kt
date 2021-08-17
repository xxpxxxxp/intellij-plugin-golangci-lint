package com.ypwang.plugin

import org.junit.Test
import org.junit.Assert
import java.nio.charset.Charset

class ProcessWrapperTest {
    @Test
    fun runWithArgumentsTest() {
        // let's run something common in Linux/Windows/Unix/Mac
        val rst = fetchProcessOutput(ProcessBuilder(listOf("nslookup", "www.google.com", "8.8.8.8")).start(), Charset.defaultCharset()).stdout.lines().toSet()
        Assert.assertTrue(rst.contains("Server:\t\t8.8.8.8"))
        Assert.assertTrue(rst.contains("Non-authoritative answer:"))
        Assert.assertTrue(rst.contains("Name:\twww.google.com"))
    }
}