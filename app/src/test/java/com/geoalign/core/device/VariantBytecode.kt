package com.geoalign.core.device

import java.io.File
import java.util.zip.ZipFile

/**
 * Reads the *compiled output of the variant currently under test*, so a test can assert what is or
 * is not in the artifact rather than what some flag says about it.
 *
 * This exists because "the experimental device profiles are absent from the `play` source set, not
 * hidden in its UI" (issue #4) is a claim about bytecode. A test that asserts
 * `capabilities.experimentalDeviceProfiles == false`, or that `DeviceProfiles.ALL.size == 1`, would
 * pass just as happily against a build that ships every preset string in the APK and filters them
 * at render time. Only looking at the class files distinguishes the two.
 *
 * The classes root is derived from `DeviceProfiles`' own [java.security.CodeSource] rather than a
 * hardcoded `build/` path, so it follows whichever variant Gradle is running and does not need
 * updating when AGP moves its intermediates around.
 *
 * Lives in the shared `src/test` source set because both `src/testPlay` (asserts absence) and
 * `src/testCommunity` (asserts presence, which is what stops the absence check from passing
 * vacuously) need it.
 */
object VariantBytecode {

    /**
     * @param classFilesScanned how many `.class` files were actually read. A zero here means the
     *   scan found nothing to look at, which would make any "marker not found" result meaningless.
     * @param markersFound which of the requested markers occur in at least one of those files.
     */
    data class Scan(val classFilesScanned: Int, val markersFound: Set<String>)

    /**
     * Search the variant's production class files for each of [markers], as raw bytes.
     *
     * Class-file constant pools store strings in modified UTF-8; for the ASCII identifiers this is
     * used with, a byte-level substring search over an ISO-8859-1 decode is exact and needs no
     * bytecode parser.
     */
    fun scanProductionClasses(markers: List<String>): Scan {
        val root = productionClassesRoot()
        var scanned = 0
        val found = mutableSetOf<String>()

        forEachClassFile(root) { bytes ->
            scanned++
            val text = String(bytes, Charsets.ISO_8859_1)
            for (marker in markers) {
                if (marker !in found && text.contains(marker)) found += marker
            }
        }
        return Scan(scanned, found)
    }

    /**
     * The compiled-classes root that `DeviceProfiles` was loaded from. Under AGP, `main` and the
     * selected flavor source set compile into the *same* output, so anything the flavor contributed
     * is inside this root too — which is precisely what makes the absence check meaningful.
     */
    private fun productionClassesRoot(): File {
        val source = DeviceProfiles::class.java.protectionDomain?.codeSource
            ?: error("no CodeSource for DeviceProfiles; cannot locate the variant's class output")
        val location = source.location
            ?: error("CodeSource for DeviceProfiles has no location")
        return File(location.toURI())
    }

    private fun forEachClassFile(root: File, body: (ByteArray) -> Unit) {
        when {
            root.isDirectory -> root.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { body(it.readBytes()) }

            root.isFile -> ZipFile(root).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .forEach { entry -> zip.getInputStream(entry).use { body(it.readBytes()) } }
            }

            else -> error("variant class output does not exist: $root")
        }
    }
}
