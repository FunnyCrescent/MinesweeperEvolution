package com.zminesweeper.game.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * Сообщения сетевого протокола мультиплеера.
 *
 * Формат на проводе: одна строка JSON, завершённая '\n'.
 *
 *  Клиент → Хост:
 *    {"t":"JOIN","nickname":"Alice"}
 *    {"t":"CLICK","row":3,"col":5}
 *    {"t":"FLAG","row":3,"col":5}
 *    {"t":"LEAVE"}
 *
 *  Хост → Все (или конкретному клиенту):
 *    {"t":"JOIN_ACK","nickname":"Alice","playerId":1}
 *    {"t":"LOBBY","players":[{"id":0,"name":"Host","isHost":true}, ...]}
 *    {"t":"START","mode":"drift","difficulty":"beginner","shiftInterval":10,"players":[...],"seed":12345}
 *    {"t":"STATE","engine":"...","turn":1,"elapsed":42}
 *    {"t":"OVER","reason":"exploded","loserId":1,"winnerId":-1}
 *    {"t":"GOODBYE"}
 *    {"t":"ERROR","message":"..."}
 */
sealed class Message {
    abstract fun toJson(): JSONObject

    data class Join(val nickname: String) : Message() {
        override fun toJson() = JSONObject().put("t", "JOIN").put("nickname", nickname)
    }
    data class JoinAck(val nickname: String, val playerId: Int) : Message() {
        override fun toJson() = JSONObject()
            .put("t", "JOIN_ACK")
            .put("nickname", nickname)
            .put("playerId", playerId)
    }
    data class Click(val row: Int, val col: Int) : Message() {
        override fun toJson() = JSONObject().put("t", "CLICK").put("row", row).put("col", col)
    }
    data class Flag(val row: Int, val col: Int) : Message() {
        override fun toJson() = JSONObject().put("t", "FLAG").put("row", row).put("col", col)
    }
    data class Chord(val row: Int, val col: Int) : Message() {
        override fun toJson() = JSONObject().put("t", "CHORD").put("row", row).put("col", col)
    }
    object Leave : Message() {
        override fun toJson() = JSONObject().put("t", "LEAVE")
    }

    data class PlayerInfo(val id: Int, val name: String, val isHost: Boolean) {
        fun toJson() = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("isHost", isHost)
        companion object {
            fun fromJson(o: JSONObject) = PlayerInfo(
                o.optInt("id"), o.optString("name"), o.optBoolean("isHost")
            )
        }
    }

    data class Lobby(val players: List<PlayerInfo>) : Message() {
        override fun toJson(): JSONObject {
            val arr = JSONArray()
            for (p in players) arr.put(p.toJson())
            return JSONObject().put("t", "LOBBY").put("players", arr)
        }
    }

    data class Start(
        val mode: String,
        val difficulty: String,
        val shiftInterval: Int,
        val players: List<PlayerInfo>,
        val hostSeed: Long,
    ) : Message() {
        override fun toJson(): JSONObject {
            val arr = JSONArray()
            for (p in players) arr.put(p.toJson())
            return JSONObject()
                .put("t", "START")
                .put("mode", mode)
                .put("difficulty", difficulty)
                .put("shiftInterval", shiftInterval)
                .put("players", arr)
                .put("seed", hostSeed)
        }
    }

    /** Полное состояние движка + чей ход + прошедшее время. */
    data class State(val engine: String, val turn: Int, val elapsed: Int) : Message() {
        override fun toJson() = JSONObject()
            .put("t", "STATE")
            .put("engine", engine)
            .put("turn", turn)
            .put("elapsed", elapsed)
    }

    data class Over(val reason: String, val loserId: Int, val winnerId: Int) : Message() {
        override fun toJson() = JSONObject()
            .put("t", "OVER")
            .put("reason", reason)
            .put("loserId", loserId)
            .put("winnerId", winnerId)
    }

    data class Error(val message: String) : Message() {
        override fun toJson() = JSONObject().put("t", "ERROR").put("message", message)
    }

    object Goodbye : Message() {
        override fun toJson() = JSONObject().put("t", "GOODBYE")
    }

    companion object {
        /** Универсальный парсер — понимает все типы сообщений в обоих направлениях. */
        fun parse(line: String): Message? = try {
            val o = JSONObject(line)
            when (o.optString("t")) {
                "JOIN"     -> Join(o.optString("nickname"))
                "JOIN_ACK" -> JoinAck(o.optString("nickname"), o.optInt("playerId"))
                "CLICK"    -> Click(o.optInt("row"), o.optInt("col"))
                "FLAG"     -> Flag(o.optInt("row"), o.optInt("col"))
                "CHORD"    -> Chord(o.optInt("row"), o.optInt("col"))
                "LEAVE"    -> Leave
                "GOODBYE"  -> Goodbye
                "ERROR"    -> Error(o.optString("message"))
                "LOBBY"    -> Lobby(
                    o.optJSONArray("players")?.let { arr ->
                        (0 until arr.length()).map { PlayerInfo.fromJson(arr.getJSONObject(it)) }
                    } ?: emptyList()
                )
                "START"    -> Start(
                    mode = o.optString("mode"),
                    difficulty = o.optString("difficulty"),
                    shiftInterval = o.optInt("shiftInterval"),
                    players = o.optJSONArray("players")?.let { arr ->
                        (0 until arr.length()).map { PlayerInfo.fromJson(arr.getJSONObject(it)) }
                    } ?: emptyList(),
                    hostSeed = o.optLong("seed"),
                )
                "STATE"    -> State(
                    engine = o.optString("engine"),
                    turn = o.optInt("turn"),
                    elapsed = o.optInt("elapsed"),
                )
                "OVER"     -> Over(
                    reason = o.optString("reason"),
                    loserId = o.optInt("loserId"),
                    winnerId = o.optInt("winnerId"),
                )
                else       -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
