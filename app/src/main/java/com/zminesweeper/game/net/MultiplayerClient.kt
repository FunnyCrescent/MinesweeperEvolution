package com.zminesweeper.game.net

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Сетевой клиент (гость-сторона мультиплеера).
 *
 *  - Подключается к хосту по IP:port.
 *  - Читает входящие сообщения и отдаёт их в [onMessage].
 *  - Отправляет сообщения через [send].
 *  - Колбэки [onMessage] / [onDisconnect] / [onError] можно менять на лету.
 */
class MultiplayerClient {
    private var socket: Socket? = null
    private var writer: OutputStreamWriter? = null
    @Volatile private var running = false
    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MpClient-Reader").apply { isDaemon = true }
    }

    @Volatile var onMessage: (Message) -> Unit = { _ -> }
    @Volatile var onDisconnect: () -> Unit = { }
    @Volatile var onError: (String) -> Unit = { _ -> }

    /** Подключиться к [hostIp]:[port]. Таймаут — 5 сек. */
    fun connect(hostIp: String, port: Int = MultiplayerServer.DEFAULT_PORT) {
        if (running) return
        try {
            val s = Socket()
            s.connect(InetSocketAddress(hostIp, port), 5000)
            socket = s
            writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            running = true
            pool.execute { readLoop(s) }
        } catch (e: IOException) {
            onError("Не удалось подключиться к $hostIp:$port — ${e.message}")
        }
    }

    fun send(message: Message) {
        val w = writer ?: return
        try {
            synchronized(w) {
                w.write(message.toJson().toString())
                w.write("\n")
                w.flush()
            }
        } catch (_: Exception) {
            disconnect()
        }
    }

    fun disconnect() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
        onDisconnect()
    }

    private fun readLoop(s: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (running) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                Message.parse(line)?.let { onMessage(it) }
            }
        } catch (_: Exception) {
            // ignore
        } finally {
            if (running) {
                running = false
                onDisconnect()
            }
        }
    }
}
