package com.zminesweeper.game.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket-клиент к relay-серверу. Используется для кроссплатформенного мультиплеера
 * (Android APK + HTML веб-версия).
 *
 * Один класс работает и как хост, и как клиент — разница только в начальном сообщении:
 *  - Хост шлёт CREATE_ROOM, получает ROOM_CREATED.
 *  - Клиент шлёт JOIN_ROOM, получает JOIN_ACK.
 *
 * После подключения оба используют один и тот же протокол:
 *  - broadcast(message): разослать всем в комнате (только хост).
 *  - sendToHost(message): отправить только хосту (только клиент).
 *  - onMessage: входящие сообщения.
 *
 * Колбэки вызываются на потоке OkHttp dispatcher; если нужно дёрнуть UI — оберни в Handler.post.
 */
class WebSocketRelay(
    private val serverUrl: String,
) {
    companion object {
        const val MAX_PLAYERS = 5
    }

    @Volatile var onOpen: () -> Unit = {}
    @Volatile var onClose: () -> Unit = {}
    @Volatile var onError: (String) -> Unit = { _ -> }
    @Volatile var onMessage: (JSONObject) -> Unit = { _ -> }

    private var client: OkHttpClient? = null
    @Volatile private var ws: WebSocket? = null
    @Volatile var isOpen: Boolean = false
        private set

    fun connect() {
        if (ws != null) return
        try {
            val c = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)  // no read timeout for long-lived WS
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
            client = c
            val req = Request.Builder().url(serverUrl).build()
            ws = c.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isOpen = true
                    this@WebSocketRelay.onOpen()
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val obj = JSONObject(text)
                        onMessage(obj)
                    } catch (e: Exception) {
                        // malformed JSON — ignore
                    }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    onMessage(webSocket, bytes.utf8())
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isOpen = false
                    onError(t.message ?: "WebSocket failure")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isOpen = false
                    onClose()
                }
            })
        } catch (e: Exception) {
            onError(e.message ?: "connect failed")
        }
    }

    fun send(obj: JSONObject): Boolean {
        val w = ws ?: return false
        return try { w.send(obj.toString()) } catch (e: Exception) { false }
    }

    fun disconnect() {
        try { ws?.close(1000, "client disconnect") } catch (e: Exception) {}
        ws = null
        try { client?.dispatcher?.executorService?.shutdown() } catch (e: Exception) {}
        client = null
        isOpen = false
    }
}
