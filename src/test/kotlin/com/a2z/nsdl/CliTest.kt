package com.a2z.nsdl

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
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

    @Test
    fun `shell creates connects powers and inspects a switched DHCP network`() {
        val script = """
            types
            create printer printer1 bootMs=0
            create gateway gateway address=10.0.0.1 poolStart=10.0.0.100 poolEnd=10.0.0.110 router=10.0.0.1
            create ethernet-switch switch1
            create cat5-cable printer-cable
            create cat5-cable gateway-cable
            connect printer-cable printer1.eth0 switch1.port1
            connect gateway-cable gateway.eth0 switch1.port2
            power-on switch1
            power-on gateway
            power-on printer1
            advance 5000
            inspect printer1.eth0
            list
            quit
        """.trimIndent()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exit = Cli.run(
            arrayOf("shell", "--seed", "7"),
            PrintStream(stdout),
            PrintStream(stderr),
            ByteArrayInputStream(script.toByteArray()),
        )

        assertEquals(0, exit)
        assertEquals("", stderr.toString())
        assertTrue(stdout.toString().contains("NSDL interactive shell"), stdout.toString())
        assertTrue(stdout.toString().contains("\"name\":\"ethernet-switch\""), stdout.toString())
        assertTrue(stdout.toString().contains("\"address\":\"10.0.0.100\""), stdout.toString())
        assertTrue(stdout.toString().contains("\"id\":\"switch1\""), stdout.toString())
    }

    @Test
    fun `scenario list prints the names of the built-in teaching scenarios`() {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exit = Cli.run(arrayOf("scenario", "list"), PrintStream(stdout), PrintStream(stderr))

        assertEquals(0, exit)
        assertEquals("", stderr.toString())
        assertTrue(stdout.toString().lines().contains("print-job"), stdout.toString())
    }

    @Test
    fun `scenario run prints a PASSED report and exits 0 for a passing scenario`() {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exit = Cli.run(arrayOf("scenario", "run", "print-job", "--seed", "7"), PrintStream(stdout), PrintStream(stderr))

        assertEquals(0, exit)
        assertEquals("", stderr.toString())
        assertTrue(stdout.toString().contains("scenario: print-job"), stdout.toString())
        assertTrue(stdout.toString().contains("status: PASSED"), stdout.toString())
    }

    @Test
    fun `scenario run reports an unknown scenario name as a usage error`() {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exit = Cli.run(arrayOf("scenario", "run", "bogus"), PrintStream(stdout), PrintStream(stderr))

        assertEquals(2, exit)
        assertTrue(stderr.toString().contains("unknown scenario"), stderr.toString())
    }
}
