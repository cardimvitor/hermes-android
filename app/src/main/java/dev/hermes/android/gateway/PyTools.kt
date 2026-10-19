package dev.hermes.android.gateway

import android.content.Context
import android.os.Build
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Gives the agent's shell a `python3`. The interpreter is the CPython that Chaquopy embeds in the app;
 * `libhermespy.so` (a native executable shipped in jniLibs) runs it as a normal program. The standard
 * library comes from the Chaquopy archives in the APK, extracted once to a real directory.
 */
object PyTools {
    private const val STDLIB_VERSION = "1"
    private const val LAUNCHER = "libhermespy.so"

    /** Prepares the stdlib and the `python3` link. Returns (bin directory for PATH, PYTHONHOME). */
    fun prepare(context: Context): Pair<String, String> {
        val home = File(context.filesDir, "pyhome")
        extractStdlib(context, home)

        val bin = File(context.filesDir, "bin").apply { mkdirs() }
        val link = File(bin, "python3").toPath()
        // Recreated on every start: the native library directory changes when the app is updated.
        Files.deleteIfExists(link)
        Files.createSymbolicLink(link, File(context.applicationInfo.nativeLibraryDir, LAUNCHER).toPath())
        return bin.absolutePath to home.absolutePath
    }

    private fun extractStdlib(context: Context, home: File) {
        val marker = File(home, "stdlib.version")
        if (marker.exists() && marker.readText() == STDLIB_VERSION) return

        home.deleteRecursively()
        val lib = File(home, "lib/python3.14").apply { mkdirs() }
        val abi = Build.SUPPORTED_ABIS.first()
        for (archive in listOf("chaquopy/stdlib-common.imy", "chaquopy/stdlib-$abi.imy")) {
            ZipInputStream(context.assets.open(archive).buffered()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    if (!entry.isDirectory) {
                        // Native stdlib modules are flat in the archive and belong in lib-dynload.
                        val target = if (entry.name.endsWith(".so")) {
                            File(lib, "lib-dynload/" + entry.name.substringAfterLast('/'))
                        } else {
                            File(lib, entry.name)
                        }
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        marker.writeText(STDLIB_VERSION)
    }
}
