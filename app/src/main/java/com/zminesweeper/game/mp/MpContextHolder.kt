package com.zminesweeper.game.mp

import com.zminesweeper.game.net.Message
import com.zminesweeper.game.net.MultiplayerClient
import com.zminesweeper.game.net.MultiplayerServer
import com.zminesweeper.game.net.WebSocketRelay

/**
 * Синглтон-мостик для передачи server/client/relay между активностями.
 *
 *  - LobbyHostActivity → MpGameActivity: через [server] (TCP host-authoritative)
 *  - JoinClientActivity → MpGameActivity: через [client] (TCP client)
 *  - RelayHostActivity → MpGameActivity: через [relay] + [relayIsHost]=true
 *  - RelayJoinActivity → MpGameActivity: через [relay] + [relayIsHost]=false
 *
 *  [clientMessageHandler] — для проброса входящих от клиентов сообщений в активную
 *  MpGameActivity (для relay-хоста, который держит Lobby и игру одновременно).
 */
object MpContextHolder {
    @Volatile var server: MultiplayerServer? = null
    @Volatile var client: MultiplayerClient? = null
    @Volatile var relay: WebSocketRelay? = null
    @Volatile var relayIsHost: Boolean = false

    /** (clientId, Message) → вызывается когда relay-хост получает CLICK/FLAG/CHORD от клиента. */
    @Volatile var clientMessageHandler: ((Int, Message) -> Unit)? = null
}
