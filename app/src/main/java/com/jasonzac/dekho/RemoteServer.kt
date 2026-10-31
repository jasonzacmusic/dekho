package com.jasonzac.dekho

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/** Serves the remote page to phones and computers on the same Wi-Fi. */
class RemoteServer(private val service: HomeButtonService, private val remote: TvRemote) {

    private val prefs = Prefs(service)
    private val shelf = Shelf(service)
    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null

    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(PORT))
        } catch (e: java.io.IOException) {
            return
        }
        server = socket
        thread(name = "dekho-server") {
            try {
                while (true) {
                    val client = socket.accept()
                    thread {
                        try {
                            answer(client)
                        } catch (e: Exception) {
                            client.close()
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                socket.close()
            }
        }
    }

    fun stop() {
        server?.close()
    }

    private fun answer(client: Socket) = client.use { socket ->
        socket.soTimeout = 5000
        val input = socket.getInputStream().bufferedReader()
        val request = input.readLine()?.split(" ") ?: return
        var length = 0
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(":").trim().toInt()
        }
        // The page percent-encodes what it sends, so one character is one byte here.
        val body = CharArray(length).also { chars ->
            var got = 0
            while (got < length) {
                val n = input.read(chars, got, length - got)
                if (n < 0) break
                got += n
            }
        }.concatToString()
        val parts = request[1].split("?", limit = 2)
        val query = parts.getOrElse(1) { "" }.split("&").associate {
            it.substringBefore("=") to URLDecoder.decode(it.substringAfter("=", ""), "UTF-8")
        }
        val path = parts[0].trim('/').split("/")
        val out = socket.getOutputStream()
        val (status, type, content) = if (path[0] != prefs.remoteToken) {
            Triple("404 Not Found", "text/plain", "Not found")
        } else {
            try {
                handle(path.getOrElse(1) { "" }, query, body)
            } catch (e: Exception) {
                Triple("500 Error", "application/json", JSONObject().put("error", e.message).toString())
            }
        }
        val bytes = content.toByteArray()
        out.write("HTTP/1.1 $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun handle(route: String, query: Map<String, String>, body: String): Triple<String, String, String> {
        when (route) {
            "" -> return Triple(
                "200 OK", "text/html",
                service.resources.openRawResource(R.raw.remote).bufferedReader().readText(),
            )
            "status" -> return json(status())
            "key" -> remote.key(query.getValue("c").toInt())
            "type" -> remote.type(URLDecoder.decode(body, "UTF-8"))
            "app" -> main.post { service.switchTo(query.getValue("p")) }
            "input" -> main.post { service.switchToPort(query.getValue("i")) }
            "strip" -> main.post { service.toggleStrip() }
            "pair" -> return json(JSONObject().put("paired", remote.pair()))
        }
        return json(JSONObject().put("ok", true))
    }

    private fun json(value: JSONObject) = Triple("200 OK", "application/json", value.toString())

    private fun status() = JSONObject()
        .put("paired", remote.paired)
        .put("apps", JSONArray(shelf.apps().filter { it.packageName != null }.map {
            JSONObject().put("name", it.name).put("id", it.packageName)
        }))
        .put("inputs", JSONArray(shelf.ports().map {
            JSONObject().put("name", it.device ?: it.label).put("id", it.id).put("state", it.state)
        }))

    companion object {
        const val PORT = 8088

        fun address(context: Context): String? {
            val ip = NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: return null
            return "http://$ip:$PORT/${Prefs(context).remoteToken}"
        }
    }
}
