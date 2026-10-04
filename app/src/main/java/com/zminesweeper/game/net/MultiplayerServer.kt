package com.zminesweeper.game.net

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Сетевой сервер (хост-сторона мультиплеера).
 *
 *  - Слушает TCP на указанном порту (по умолчанию 7777).
 *  - Принимает подключения от клиентов (до [MAX_PLAYERS] - 1 клиента, т.к. хост сам игрок).
 *  - Каждое подключение обслуживается в отдельном потоке.
 *  - Колбэки [onClientMessage] / [onClientConnected] / [onClientDisconnected] / [onError]
 *    можно менять на лету — это позволяет лобби передать сервер игре со своим обработчиком.
 *
 *  Сам хост не использует сокет — он обрабатывает свой собственный ввод локально.
 */
class MultiplayerServer(
    private val port: Int = DEFAULT_PORT,
) {
    companion object {
        const val DEFAULT_PORT = 7777
        const val MAX_PLAYERS = 5   // включая хоста
    }

    @Volatile var onClientMessage: (clientId: Int, msg: Message) -> Unit = { _, _ -> }
    @Volatile var onClientConnected: (clientId: Int, addr: String) -> Unit = { _, _ -> }
    @Volatile var onClientDisconnected: (clientId: Int) -> Unit = { _ -> }
    @Volatile var onError: (String) -> Unit = { _ -> }

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "MpServer-Worker").apply { isDaemon = true }
    }
    private val listenThread = Thread({ listen() }, "MpServer-Accept").apply { isDaemon = true }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val nextClientId = AtomicInteger(1)  // 0 = хост

    /** clientId → connection (client) */
    private val clients = ConcurrentHashMap<Int, ClientConn>()

    fun start() {
        if (running) return
        try {
            serverSocket = ServerSocket(port).apply { reuseAddress = true }
            running = true
            listenThread.start()
        } catch (e: IOException) {
            onError("Не удалось открыть порт $port: ${e.message}")
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        synchronized(clients) {
            for ((_, c) in clients) {
                c.send(Message.Goodbye.toJson().toString())
                c.close()
            }
        }
        clients.clear()
    }

    fun broadcast(message: Message) {
        val line = message.toJson().toString()
        for ((_, c) in clients) {
            c.send(line)
        }
    }

    /** Сказать конкретному клиенту. */
    fun sendTo(clientId: Int, message: Message) {
        clients[clientId]?.send(message.toJson().toString())
    }

    /** Список подключённых клиентов (без хоста). */
    fun connectedClientIds(): List<Int> = clients.keys.toList()

    private fun listen() {
        while (running) {
            val s: Socket = try {
                serverSocket?.accept() ?: return
            } catch (_: IOException) {
                if (running) onError("accept() failed")
                return
            }
            if (clients.size >= MAX_PLAYERS - 1) {
                try {
                    val w = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
                    w.write(Message.Error("Лобби заполнено (макс. $MAX_PLAYERS игроков).").toJson().toString() + "\n")
                    w.write(Message.Goodbye.toJson().toString() + "\n")
                    w.flush()
                    s.close()
                } catch (_: Exception) {}
                continue
            }
            val cid = nextClientId.getAndIncrement()
            val conn = ClientConn(cid, s,
                onMessage = { msg -> onClientMessage(cid, msg) },
                onDisconnect = {
                    clients.remove(cid)
                    onClientDisconnected(cid)
                },
                onError = { msg -> onError(msg) }
            )
            clients[cid] = conn
            onClientConnected(cid, s.inetAddress.hostAddress ?: "?")
            pool.execute { conn.run() }
        }
    }

    private class ClientConn(
        val id: Int,
        val socket: Socket,
        val onMessage: (Message) -> Unit,
        val onDisconnect: () -> Unit,
        val onError: (String) -> Unit,
    ) {
        private val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
        @Volatile private var closed = false

        fun send(line: String) {
            if (closed) return
            try {
                synchronized(writer) {
                    writer.write(line)
                    writer.write("\n")
                    writer.flush()
                }
            } catch (_: Exception) {
                close()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            try { socket.close() } catch (_: Exception) {}
            onDisconnect()
        }

        fun run() {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                while (!closed) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    Message.parse(line)?.let { onMessage(it) }
                }
            } catch (_: Exception) {
                // ignore
            } finally {
                close()
            }
        }
    }
}
