package com.a2z.nsdl

import com.a2z.nsdl.ipc.IpcClient
import com.a2z.nsdl.ipc.IpcServer
import com.a2z.nsdl.web.HttpSimulationServer
import java.io.InputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch

object Cli {
    fun run(args: Array<String>, out: PrintStream, err: PrintStream, input: InputStream = System.`in`): Int = try {
        when (args.firstOrNull()) {
            "serve" -> serve(args.drop(1), out)
            "example" -> example(args.drop(1), out)
            "demo" -> demo(args.drop(1), out)
            "shell" -> shell(args.drop(1), input, out)
            "web" -> web(args.drop(1), out)
            else -> usage(err)
        }
    } catch (e: IllegalArgumentException) {
        err.println("error: ${e.message}")
        2
    }

    private fun serve(args: List<String>, out: PrintStream): Int {
        val options = options(args, setOf("--port", "--seed"))
        val port = options["--port"]?.toIntOrNull() ?: 0
        val seed = options["--seed"]?.toLongOrNull() ?: 0L
        require(port in 0..65535) { "--port must be between 0 and 65535" }

        val composition = Composition(seed)
        val server = IpcServer(composition.runtime, port)
        val stopped = CountDownLatch(1)
        val shutdown = Thread {
            server.stop()
            composition.close()
            stopped.countDown()
        }
        Runtime.getRuntime().addShutdownHook(shutdown)
        server.start()
        out.println("NSDL_IPC_LISTENING port=${server.port}")
        out.flush()
        try {
            stopped.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            if (Runtime.getRuntime().removeShutdownHook(shutdown)) shutdown.run()
        }
        return 0
    }

    private fun example(args: List<String>, out: PrintStream): Int {
        val options = options(args, setOf("--port"))
        val port = options["--port"]?.toIntOrNull()
            ?: throw IllegalArgumentException("example requires --port N")
        require(port in 1..65535) { "--port must be between 1 and 65535" }
        IpcClient("127.0.0.1", port).use { runExample(it, out) }
        return 0
    }

    private fun demo(args: List<String>, out: PrintStream): Int {
        require(args.isEmpty()) { "demo takes no options" }
        val composition = Composition(7L)
        val server = IpcServer(composition.runtime, 0)
        server.start()
        return try {
            IpcClient("127.0.0.1", server.port).use { runExample(it, out) }
            0
        } finally {
            server.stop()
            composition.close()
        }
    }

    private fun shell(args: List<String>, input: InputStream, out: PrintStream): Int {
        val options = options(args, setOf("--port", "--seed"))
        val requestedPort = options["--port"]?.let {
            it.toIntOrNull() ?: throw IllegalArgumentException("--port must be an integer")
        }
        val seed = options["--seed"]?.let {
            it.toLongOrNull() ?: throw IllegalArgumentException("--seed must be an integer")
        } ?: 0L
        require(requestedPort == null || requestedPort in 1..65535) { "--port must be between 1 and 65535" }

        if (requestedPort != null) {
            IpcClient("127.0.0.1", requestedPort).use { InteractiveShell(it, input, out).run() }
            return 0
        }

        val composition = Composition(seed)
        val server = IpcServer(composition.runtime, 0)
        server.start()
        return try {
            IpcClient("127.0.0.1", server.port).use { InteractiveShell(it, input, out).run() }
            0
        } finally {
            server.stop()
            composition.close()
        }
    }

    private fun web(args: List<String>, out: PrintStream): Int {
        val options = options(args, setOf("--port", "--seed"))
        val port = options["--port"]?.toIntOrNull() ?: 8080
        val seed = options["--seed"]?.toLongOrNull() ?: 0L
        require(port in 0..65535) { "--port must be between 0 and 65535" }

        val composition = Composition(seed)
        val server = HttpSimulationServer(composition.runtime, port)
        val stopped = CountDownLatch(1)
        val shutdown = Thread {
            server.stop()
            composition.close()
            stopped.countDown()
        }
        Runtime.getRuntime().addShutdownHook(shutdown)
        server.start()
        out.println("NSDL_WEB_LISTENING url=http://127.0.0.1:${server.port}/")
        out.flush()
        try {
            stopped.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            if (Runtime.getRuntime().removeShutdownHook(shutdown)) shutdown.run()
        }
        return 0
    }

    private fun runExample(client: IpcClient, out: PrintStream) {
        fun request(op: String, params: Map<String, Any?> = emptyMap()): Map<String, Any?> {
            val reply = client.request(op, params)
            check(reply["type"] == "result") { "$op failed: $reply" }
            out.println("$op changed=${reply["changed"]}")
            return reply
        }

        request("create", mapOf("id" to "printer", "type" to "printer"))
        request("create", mapOf(
            "id" to "server", "type" to "dhcp-server-host",
            "props" to mapOf("address" to "10.0.0.1", "poolStart" to "10.0.0.100", "poolEnd" to "10.0.0.110"),
        ))
        request("create", mapOf("id" to "cable", "type" to "cat5-cable"))
        request("connect", mapOf("cableId" to "cable", "a" to "printer.eth0", "b" to "server.eth0"))
        request("subscribe", mapOf("from" to 0L, "capacity" to 100L))
        request("powerOn", mapOf("id" to "server"))
        request("powerOn", mapOf("id" to "printer"))
        request("advance", mapOf("durationMs" to 5_000L))

        val inspected = request("inspect", mapOf("id" to "printer.eth0"))
        val state = (inspected["data"] as Map<*, *>)["state"] as Map<*, *>
        val address = (state["ipv4"] as Map<*, *>)["address"]
        out.println("printer.eth0 address=$address")
        while (true) {
            val event = client.nextPushed(50) ?: break
            out.println("event seq=${event["seq"]} source=${event["source"]} type=${event["eventType"]}")
        }
        request("disconnect", mapOf("cableId" to "cable"))
        request("disconnect", mapOf("cableId" to "cable"))
    }

    private fun options(args: List<String>, allowed: Set<String>): Map<String, String> {
        require(args.size % 2 == 0) { "options must be --name value pairs" }
        return args.chunked(2).associate { pair ->
            require(pair[0] in allowed) { "unknown option '${pair[0]}'" }
            pair[0] to pair[1]
        }
    }

    private fun usage(err: PrintStream): Int {
        err.println("usage: nsdl serve [--port N] [--seed N] | web [--port N] [--seed N] | example --port N | demo | shell [--port N] [--seed N]")
        return 2
    }
}
