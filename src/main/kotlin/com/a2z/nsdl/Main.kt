package com.a2z.nsdl

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = Cli.run(args, System.out, System.err)
    if (exitCode != 0) exitProcess(exitCode)
}
