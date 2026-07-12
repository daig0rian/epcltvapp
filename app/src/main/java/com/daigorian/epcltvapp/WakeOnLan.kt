package com.daigorian.epcltvapp

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object WakeOnLan {
    private const val MAGIC_PACKET_HEADER_SIZE = 6
    private const val MAC_REPEAT_COUNT = 16

    /** マジックパケットをブロードキャストで送信する。ネットワークI/Oのため呼び出し側でバックグラウンドスレッドから呼ぶこと。 */
    fun send(macAddress: String, broadcastAddress: String = "255.255.255.255", port: Int = 9) {
        val macBytes = macAddress.split(Regex("[:\\-]"))
            .map { it.toInt(16).toByte() }
            .toByteArray()

        val packetBytes = ByteArray(MAGIC_PACKET_HEADER_SIZE + MAC_REPEAT_COUNT * macBytes.size)
        for (i in 0 until MAGIC_PACKET_HEADER_SIZE) {
            packetBytes[i] = 0xFF.toByte()
        }
        for (i in 0 until MAC_REPEAT_COUNT) {
            System.arraycopy(macBytes, 0, packetBytes, MAGIC_PACKET_HEADER_SIZE + i * macBytes.size, macBytes.size)
        }

        DatagramSocket().use { socket ->
            socket.broadcast = true
            val address = InetAddress.getByName(broadcastAddress)
            socket.send(DatagramPacket(packetBytes, packetBytes.size, address, port))
        }
    }
}
