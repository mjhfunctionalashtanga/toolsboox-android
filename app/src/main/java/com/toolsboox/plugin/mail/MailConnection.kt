package com.toolsboox.plugin.mail

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** An unexpected/negative server response, an auth rejection, a bad config, or a dropped socket. */
class MailException(message: String) : Exception(message)

/**
 * A TLS (or plain) line-oriented socket for the mail protocols. IMAP and SMTP are both CRLF line
 * protocols with occasional counted byte blocks (IMAP literals), so this vends [readLine] and
 * [readBytes] over a single buffered input, plus [send]. Implicit TLS only (IMAP 993, SMTP 465).
 *
 * A direct port of iOS App/Mail/MailConnection.swift, but over a blocking
 * javax.net.ssl.SSLSocket instead of NWConnection — cleaner in three ways the iOS side had to work
 * around by hand: [start]'s connect throws AT ONCE on a DNS / no-route failure (the ".waiting
 * fast-fail" the iOS state handler special-cased), a connect timeout bounds an unreachable host,
 * and a read timeout (soTimeout) bounds a server that accepts the socket then goes silent — the
 * guard iOS got from `withMailTimeout`. Every call here BLOCKS; callers run it on Dispatchers.IO.
 */
class MailConnection(
    private val host: String,
    private val port: Int,
    private val useTLS: Boolean,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null

    fun start() {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), connectTimeoutMs)   // throws fast on no-route / DNS
            raw.soTimeout = readTimeoutMs                                   // bounds a silent server
            val s: Socket = if (useTLS) {
                (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, host, port, true)                    // layered over the live socket
                    .also { (it as SSLSocket).startHandshake() }
            } else raw
            socket = s
            input = BufferedInputStream(s.getInputStream())
            output = s.getOutputStream()
        } catch (e: Exception) {
            // A bad cert / handshake reset / stream-open failure would otherwise leak the raw socket
            // (and the layered SSLSocket): `socket` is still null, so a later close() is a no-op.
            try { raw.close() } catch (_: Exception) { }
            try { socket?.close() } catch (_: Exception) { }
            throw e
        }
    }

    fun close() { try { socket?.close() } catch (_: Exception) { } }

    // MARK: - Reading

    /** One CRLF-terminated line (the terminator stripped), decoded as UTF-8. */
    fun readLine(): String {
        val inp = input ?: throw MailException("The connection closed.")
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = inp.read()
            if (b == -1) { if (buf.size() == 0) throw MailException("The connection closed."); break }
            if (b == 0x0A) break                       // LF ends the line
            buf.write(b)
        }
        var bytes = buf.toByteArray()
        if (bytes.isNotEmpty() && bytes[bytes.size - 1] == 0x0D.toByte()) bytes = bytes.copyOf(bytes.size - 1) // strip CR
        return String(bytes, Charsets.UTF_8)
    }

    /** Exactly [n] bytes (an IMAP literal). */
    fun readBytes(n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        val inp = input ?: throw MailException("The connection closed.")
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = inp.read(out, read, n - read)
            if (r == -1) throw MailException("The connection closed.")
            read += r
        }
        return out
    }

    // MARK: - Writing

    fun send(line: String) {
        val o = output ?: throw MailException("The connection closed.")
        o.write((line + "\r\n").toByteArray(Charsets.UTF_8)); o.flush()
    }

    fun sendRaw(data: ByteArray) {
        val o = output ?: throw MailException("The connection closed.")
        o.write(data); o.flush()
    }
}
