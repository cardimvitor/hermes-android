package dev.hermes.android.device

import dev.hermes.android.HermesApplication
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Wireless ADB on the phone itself. Runs `libadb.so`, the reduced AOSP adb client built from source
 * (tools/adb-reduced), against the phone's own Wireless debugging port, so no computer is needed.
 *
 * Every entry point returns a JSON string: {"ok": bool, "exit_code": int, "output": string}. The Python
 * side and the activity dialog read the same shape.
 */
object AdbBridge {

    private const val BINARY = "libadb.so"
    private const val MAX_OUTPUT = 30_000

    /** Serial of the last successful connect, so later shell calls target that device. */
    @Volatile
    private var connected: String? = null

    /** True when the APK ships the adb binary for this ABI. */
    @JvmStatic
    fun available(): Boolean = binary() != null

    @JvmStatic
    fun devices(): String = run(listOf("devices", "-l"), 15)

    /** Pairs with the code shown by "Parear com código". [hostPort] is the pairing address, e.g. 127.0.0.1:37123. */
    @JvmStatic
    fun pair(hostPort: String, code: String): String = run(listOf("pair", hostPort, code), 30)

    /** Connects to the Wireless debugging port (not the pairing port). */
    @JvmStatic
    fun connect(hostPort: String): String {
        val result = run(listOf("connect", hostPort), 20)
        if (JSONObject(result).optBoolean("ok")) connected = hostPort
        return result
    }

    /** Runs one shell command on the connected device. With no connection it uses adb's only device. */
    @JvmStatic
    fun shell(command: String): String {
        val target = connected
        val args = if (target != null) listOf("-s", target) else emptyList()
        return run(args + listOf("shell", command), 120)
    }

    private fun binary(): File? {
        val context = HermesApplication.appContext ?: return null
        return File(context.applicationInfo.nativeLibraryDir, BINARY).takeIf { it.isFile && it.canExecute() }
    }

    private fun run(args: List<String>, timeoutSeconds: Long): String {
        val context = HermesApplication.appContext ?: return failure("app não iniciado")
        val exe = binary() ?: return failure("este APK não traz o adb para esta arquitetura")
        // adb keeps its key pair in $HOME/.android, so HOME must be private and writable.
        val home = File(context.filesDir, "adb-home").apply { mkdirs() }

        val process = try {
            ProcessBuilder(listOf(exe.absolutePath) + args)
                .directory(home)
                .apply {
                    environment()["HOME"] = home.absolutePath
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                }
                .start()
        } catch (e: Exception) {
            return failure("não foi possível executar o adb: $e")
        }

        // The adb server it may start inherits this pipe, so the reader is a daemon thread that is not
        // awaited for EOF. The client's own exit code decides the result.
        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line -> synchronized(output) { output.append(line).append('\n') } }
            } catch (_: Exception) {
                // The stream closes when the process is killed; whatever was read is kept.
            }
        }.apply { isDaemon = true; start() }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(500)
            return result(false, -1, "tempo esgotado depois de ${timeoutSeconds}s\n" + snapshot(output))
        }
        reader.join(500)
        return result(process.exitValue() == 0, process.exitValue(), snapshot(output))
    }

    private fun snapshot(output: StringBuilder): String = synchronized(output) { output.toString().takeLast(MAX_OUTPUT) }

    private fun failure(message: String): String = result(false, -1, message)

    private fun result(ok: Boolean, exitCode: Int, output: String): String =
        JSONObject().put("ok", ok).put("exit_code", exitCode).put("output", output).toString()
}
