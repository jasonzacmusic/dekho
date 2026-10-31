package com.jasonzac.dekho

import android.content.Context
import android.util.Base64
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Presses keys in other apps, which an ordinary app cannot do. Android TV's own remote
 * service refuses anything running on the TV, so this uses the TV's debugging connection
 * instead, the one the installer already switched on. The owner allows it once on screen.
 */
class TvRemote(context: Context) {

    private val prefs = Prefs(context)
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var nextStream = 1

    val paired get() = prefs.remotePaired

    /** Connects, waiting for the owner to press Allow when the TV asks. */
    @Synchronized
    fun pair(): Boolean = try {
        connect()
        prefs.remotePaired = true
        true
    } catch (e: IOException) {
        false
    }

    fun key(code: Int) = shell("input keyevent $code")

    /** Types into whichever box is selected on the TV. */
    fun type(text: String) {
        text.chunked(TYPE_CHUNK).forEach { part ->
            shell(part.map { letter -> "input text '${escape(letter)}'" }.joinToString(";"))
        }
    }

    private fun escape(letter: Char) = when (letter) {
        ' ' -> "%s"
        '\'' -> "'\\''"
        else -> letter.toString()
    }

    @Synchronized
    private fun shell(command: String) {
        try {
            run(command)
        } catch (e: IOException) {
            drop()
            run(command)
        }
    }

    private fun run(command: String) {
        if (socket == null) connect()
        val local = nextStream++
        send(OPEN, local, 0, "shell:$command\u0000".toByteArray())
        var remote = 0
        while (true) {
            val message = read()
            remote = message.from
            when (message.command) {
                WRTE -> send(OKAY, local, remote)
                CLSE -> {
                    send(CLSE, local, remote)
                    return
                }
            }
        }
    }

    private fun connect() {
        drop()
        val next = Socket().apply { connect(InetSocketAddress("127.0.0.1", PORT), 3000) }
        socket = next
        input = DataInputStream(next.getInputStream().buffered())
        next.soTimeout = APPROVAL_WAIT
        send(CNXN, VERSION, MAX_DATA, "host::dekho\u0000".toByteArray())
        var triedKey = false
        while (true) {
            val message = read()
            when (message.command) {
                CNXN -> {
                    next.soTimeout = REPLY_WAIT
                    return
                }
                AUTH if !triedKey -> {
                    triedKey = true
                    send(AUTH, AUTH_SIGNATURE, 0, sign(message.data))
                }
                // Not known yet: this is what puts the Allow question on the TV.
                AUTH -> send(AUTH, AUTH_PUBLIC_KEY, 0, publicKey())
            }
        }
    }

    private fun drop() {
        socket?.close()
        socket = null
    }

    // --- One message on the wire: six numbers, then the data ---

    private class Message(val command: Int, val from: Int, val data: ByteArray)

    private fun send(command: Int, first: Int, second: Int, data: ByteArray = ByteArray(0)) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(first).putInt(second).putInt(data.size)
            .putInt(data.sumOf { it.toInt() and 0xff }).putInt(command.inv())
        socket!!.getOutputStream().apply {
            write(header.array() + data)
            flush()
        }
    }

    private fun read(): Message {
        val stream = input ?: throw EOFException()
        val raw = ByteArray(24).also(stream::readFully)
        val header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.getInt()
        val from = header.getInt()
        header.getInt()
        val data = ByteArray(header.getInt()).also(stream::readFully)
        return Message(command, from, data)
    }

    // --- The key that proves it is us ---

    private fun privateKey(): PrivateKey {
        if (prefs.remoteKey == null || prefs.remotePublicKey == null) {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            prefs.remoteKey = pair.private.encoded
            prefs.remotePublicKey = pair.public.encoded
        }
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(prefs.remoteKey))
    }

    private fun sign(token: ByteArray): ByteArray = Signature.getInstance("NONEwithRSA").run {
        initSign(privateKey())
        update(SHA1_PREFIX + token)
        sign()
    }

    // The TV wants the key in the old layout its debugging service was written for.
    private fun publicKey(): ByteArray {
        privateKey()
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(prefs.remotePublicKey)) as RSAPublicKey
        val n = key.modulus
        val word = BigInteger.ONE.shiftLeft(32)
        val packed = ByteBuffer.allocate(KEY_BYTES * 2 + 12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(KEY_BYTES / 4)
            .putInt((word - n.mod(word).modInverse(word)).toInt())
            .put(littleEndian(n))
            .put(littleEndian(BigInteger.ONE.shiftLeft(KEY_BYTES * 16).mod(n)))
            .putInt(key.publicExponent.toInt())
        return Base64.encode(packed.array(), Base64.NO_WRAP) + " dekho@tv\u0000".toByteArray()
    }

    private fun littleEndian(value: BigInteger): ByteArray {
        val bytes = ByteArray(KEY_BYTES)
        val big = value.toByteArray()
        for (i in 0 until minOf(big.size, KEY_BYTES)) bytes[i] = big[big.size - 1 - i]
        return bytes
    }

    private companion object {
        const val PORT = 5555
        const val TYPE_CHUNK = 100
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val VERSION = 0x01000000
        const val MAX_DATA = 4096
        const val AUTH_SIGNATURE = 2
        const val AUTH_PUBLIC_KEY = 3
        const val APPROVAL_WAIT = 60_000
        const val REPLY_WAIT = 10_000
        const val KEY_BYTES = 256
        val SHA1_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
    }
}
