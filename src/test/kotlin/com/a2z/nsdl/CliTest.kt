package com.a2z.nsdl

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliTest {
    @Test
    fun `demo runs the example through an in-process IPC server`() {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exit = Cli.run(arrayOf("demo"), PrintStream(stdout), PrintStream(stderr))

        assertEquals(0, exit)
        assertTrue(stdout.toString().contains("10.0.0.100"), stdout.toString())
        assertTrue(stdout.toString().contains("disconnect changed=true"), stdout.toString())
        assertTrue(stdout.toString().contains("disconnect changed=false"), stdout.toString())
        assertEquals("", stderr.toString())
    }
}
