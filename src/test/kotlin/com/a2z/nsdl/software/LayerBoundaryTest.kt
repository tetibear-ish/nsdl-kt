package com.a2z.nsdl.software

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Keeps the two levels of abstraction apart at the source level:
 *
 * - the software layer may use only its own package, the simulation clock (`sim`) and the shared
 *   model (`model`: ids, snapshots, events, actions) -- never a network package;
 * - nothing below it may depend on software, so protocols stay usable without any applications.
 *
 * `com.a2z.nsdl.platform` is the one place allowed to see both.
 */
class LayerBoundaryTest {
    private val root = File("src/main/kotlin/com/a2z/nsdl")
    private val importLine = Regex("""^import\s+(com\.a2z\.nsdl\.[\w.]+)""")

    private fun imports(packageDir: String): Map<String, List<String>> =
        File(root, packageDir).walkTopDown().filter { it.extension == "kt" }.associate { file ->
            file.relativeTo(root).path to file.readLines().mapNotNull { importLine.find(it)?.groupValues?.get(1) }
        }

    @Test
    fun `software imports only software, sim and model`() {
        val allowed = listOf("com.a2z.nsdl.software.", "com.a2z.nsdl.sim.", "com.a2z.nsdl.model.")
        val files = imports("software")
        assertTrue(files.isNotEmpty(), "no software sources found from ${File(".").absolutePath}")

        val violations = files.flatMap { (file, imports) -> imports.filterNot { i -> allowed.any(i::startsWith) }.map { "$file imports $it" } }

        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `network and protocol packages never depend on software or platform`() {
        val networkPackages = listOf("sim", "model", "net", "link", "ip", "dhcp", "dns", "http", "icmp", "print", "ssh", "device")
        val violations = networkPackages.flatMap { pkg ->
            imports(pkg).flatMap { (file, imports) ->
                imports.filter { it.startsWith("com.a2z.nsdl.software.") || it.startsWith("com.a2z.nsdl.platform.") }.map { "$file imports $it" }
            }
        }

        assertEquals(emptyList<String>(), violations)
    }
}
