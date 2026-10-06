package com.zminesweeper.game.net

import com.zminesweeper.game.MinesweeperApp
import com.zminesweeper.game.R
import java.net.InetAddress
import java.net.NetworkInterface

/** Утилита для получения локальных IP-адресов (для отображения в лобби хоста). */
object NetUtil {
    fun localIpAddresses(): List<String> {
        val result = ArrayList<String>()
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    // фильтруем IPv6 (с двоеточием), оставляем только IPv4
                    if (ip.contains(":")) continue
                    result.add(ip)
                }
            }
        } catch (_: Exception) {
            // ignore
        }
        return result
    }
}

/**
 * Уникализация ника: если в [existing] уже есть [nickname] (case-insensitive),
 * добавляет суффикс (2), (3), ... до тех пор, пока не станет уникальным.
 *
 * Пример:existing=["Alice","Bob"], new="alice" → "alice (2)"
 *        existing=["Alice","alice (2)"], new="alice" → "alice (3)"
 */
fun deduplicateNickname(nickname: String, existing: List<String>): String {
    if (nickname.isBlank()) return MinesweeperApp.instance.getString(R.string.default_player_name)
    val base = nickname.trim().take(20)
    val taken = existing.map { it.trim().lowercase() }.toMutableSet()
    if (base.lowercase() !in taken) return base
    var i = 2
    while (true) {
        val candidate = "$base ($i)"
        if (candidate.lowercase() !in taken) return candidate
        i++
    }
}
