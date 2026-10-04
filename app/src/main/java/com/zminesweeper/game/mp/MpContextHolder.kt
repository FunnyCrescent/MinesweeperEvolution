package com.zminesweeper.game.mp

import com.zminesweeper.game.net.MultiplayerClient
import com.zminesweeper.game.net.MultiplayerServer

/**
 * Синглтон-мостик для передачи server/client между активностями (лобби → игра).
 *
 *  LobbyHostActivity создаёт [MultiplayerServer] и при старте игры кладёт его сюда.
 *  MpGameActivity забирает и подменяет колбэки на игровые.
 *
 *  Аналогично для [MultiplayerClient] — JoinClientActivity → MpGameActivity.
 */
object MpContextHolder {
    @Volatile var server: MultiplayerServer? = null
    @Volatile var client: MultiplayerClient? = null
}
