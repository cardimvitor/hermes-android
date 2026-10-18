// Drives the real Python gateway through the app's GatewayClient over OS pipes.
// Usage: java -cp <jars> GatewayHarnessKt <python> <upstream_repo> <home_dir>
// Prints "OK <name>" per check, or "FAIL <name>: <detail>" and exits 1.
import dev.hermes.android.gateway.GatewayClient
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

fun check(name: String, ok: Boolean, detail: () -> String = { "" }) {
    if (ok) println("OK $name") else { println("FAIL $name: ${detail()}"); exitProcess(1) }
}

fun main(args: Array<String>) {
    val (python, upstream, home) = args
    val process = ProcessBuilder(python, "-m", "tui_gateway.entry")
        .directory(java.io.File(upstream))
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .apply { environment()["HERMES_HOME"] = home }
        .start()

    val stdin = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
    val client = GatewayClient(send = { line -> synchronized(stdin) { stdin.write(line + "\n"); stdin.flush() } })

    val reader = Thread {
        BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).forEachLine { client.onLine(it) }
    }.apply { isDaemon = true; start() }

    val caps = client.announceCapabilities().get(60, TimeUnit.SECONDS)
    check("client.capabilities answered", caps.has("result"), { caps.toString() })

    val created = client.call("session.create", JSONObject().put("cols", 80)).get(60, TimeUnit.SECONDS)
    val sid = created.optJSONObject("result")?.optString("session_id").orEmpty()
    check("session.create returns id", sid.isNotEmpty(), { created.toString() })

    val listed = client.call("session.list").get(60, TimeUnit.SECONDS)
    check("session.list has result", listed.has("result"), { listed.toString() })

    val unknown = client.call("no.such.method").get(60, TimeUnit.SECONDS)
    check("unknown method -> error -32601", unknown.optJSONObject("error")?.optInt("code") == -32601, { unknown.toString() })

    stdin.close()
    check("gateway exits on EOF", process.waitFor(30, TimeUnit.SECONDS), { "still running" })
    reader.join(5000)
    println("DONE")
}
