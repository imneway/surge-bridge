import io.nekohasekai.sfa.surge.SurgeConverter
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: SurgeCli input.conf output.json" }
    try {
        var fetched = 0
        val converter = SurgeConverter { url, _ ->
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            try {
                connection.inputStream.use { stream ->
                    val bytes = stream.readNBytes(16 * 1024 * 1024 + 1)
                    require(bytes.size <= 16 * 1024 * 1024)
                    fetched++
                    bytes.toString(Charsets.UTF_8)
                }
            } finally { connection.disconnect() }
        }
        val result = converter.convert(File(args[0]).readText())
        File(args[1]).apply { parentFile?.mkdirs(); writeText(result.json); setReadable(false, false); setReadable(true, true) }
        println("Converted ${result.sourceFormat}; fetched $fetched rule sets; ${result.warnings.size} compatibility notices")
        result.warnings.forEach(::println)
    } catch (e: Exception) {
        // Never emit downloaded configuration, URLs, server names or credentials.
        System.err.println(if (e is io.nekohasekai.sfa.surge.SurgeException) e.message else "Conversion failed (${e.javaClass.simpleName}); details redacted")
        kotlin.system.exitProcess(1)
    }
}
