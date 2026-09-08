package com.vdzon.newsfeedbackend.e2e

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Minimale WebSocket-testclient bovenop de JDK-[WebSocket]: binnenkomende
 * tekstberichten worden geparsed en in een queue gezet, zodat een test er
 * blokkerend op kan wachten met [poll].
 *
 * Twee valkuilen van de JDK-listener die deze klasse afvangt:
 * - `onText` moet zelf `webSocket.request(1)` aanroepen, anders levert de
 *   verbinding na het eerste bericht niets meer af.
 * - `onText` kan fragmenten leveren; er wordt pas geparsed als `last == true`.
 */
class WsTestClient private constructor(private val mapper: ObjectMapper) : WebSocket.Listener {

    private val received = LinkedBlockingQueue<JsonNode>()
    private val fragments = StringBuilder()

    @Volatile
    private var socket: WebSocket? = null

    override fun onOpen(webSocket: WebSocket) {
        socket = webSocket
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        fragments.append(data)
        if (last) {
            val text = fragments.toString()
            fragments.setLength(0)
            received.add(mapper.readTree(text))
        }
        webSocket.request(1)
        return null
    }

    /** Geeft het volgende bericht, of `null` als er binnen [timeout] niets binnenkomt. */
    fun poll(timeout: Duration): JsonNode? = received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)

    /** Sluit de verbinding netjes (normal closure); herhaald aanroepen is veilig. */
    fun close() {
        val ws = socket ?: return
        if (ws.isOutputClosed) return
        runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "test klaar").get(5, TimeUnit.SECONDS) }
    }

    companion object {
        private val HTTP: HttpClient = HttpClient.newHttpClient()

        /**
         * Verbindt met `/ws/requests` op [port] en wacht tot de handshake rond is.
         *
         * Het [token] gaat als queryparameter mee: de handshake wordt door
         * `JwtHandshakeInterceptor` geauthenticeerd. Bij een ontbrekend of
         * ongeldig token weigert de server met 401 en faalt de future met een
         * `WebSocketHandshakeException` — er komt dan geen sessie tot stand.
         * [token] is `null` om die weigering te kunnen testen.
         */
        fun connect(port: Int, mapper: ObjectMapper, token: String?): WsTestClient {
            val client = WsTestClient(mapper)
            val query = token?.let { "?token=" + URLEncoder.encode(it, StandardCharsets.UTF_8) } ?: ""
            val ws = HTTP.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create("ws://localhost:$port/ws/requests$query"), client)
                .get(20, TimeUnit.SECONDS)
            client.socket = ws
            return client
        }
    }
}
