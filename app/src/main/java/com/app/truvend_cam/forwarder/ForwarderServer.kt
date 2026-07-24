package com.app.truvend_cam.forwarder

import com.app.truvend_cam.util.AppLog
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * TCP byte relay: listen on [listenPort], for each inbound connection open a
 * separate connection to [dvrHost]:[dvrPort] and copy bytes both ways.
 *
 * [prepareDvrSocket] runs on the outbound DVR socket before connect — reserved
 * for future [android.net.VpnService.protect] (and optional network binding).
 */
class ForwarderServer(
    private val dvrHost: String,
    private val dvrPort: Int = 554,
    private val listenPort: Int = 8554,
    private val prepareDvrSocket: (Socket) -> Unit = {},
) {
    private val pool = Executors.newCachedThreadPool()
    private val activeCount = AtomicInteger(0)
    private val totalConnections = AtomicLong(0)
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var running = false

    @Volatile
    private var listening = false

    private val lastError = AtomicReference<String?>(null)

    companion object {
        private const val TAG = "ForwarderServer"
        private const val MAX_CONNECTIONS = 8
        private const val BUFFER_SIZE = 32 * 1024
        private const val SOCKET_TIMEOUT_MS = 60_000
        private const val DVR_CONNECT_TIMEOUT_MS = 5_000
    }

    fun start() {
        if (running) return
        running = true
        listening = false
        lastError.set(null)
        AppLog.i(TAG, "Starting relay listen=$listenPort → $dvrHost:$dvrPort")
        pool.execute { acceptLoop() }
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                // Bind all interfaces — do not pin to the tunnel address.
                socket.bind(InetSocketAddress(listenPort))
                serverSocket = socket
                listening = true
                lastError.set(null)
                AppLog.i(TAG, "Listening on 0.0.0.0:$listenPort → $dvrHost:$dvrPort")

                while (running) {
                    val client = socket.accept()
                    val remote = runCatching {
                        "${client.inetAddress?.hostAddress}:${client.port}"
                    }.getOrDefault("?")
                    AppLog.i(TAG, "Inbound connection from $remote")

                    if (activeCount.get() >= MAX_CONNECTIONS) {
                        AppLog.w(TAG, "Rejecting connection — at MAX_CONNECTIONS ($MAX_CONNECTIONS)")
                        runCatching { client.close() }
                        continue
                    }

                    activeCount.incrementAndGet()
                    totalConnections.incrementAndGet()
                    pool.execute { handleConnection(client) }
                }
            } catch (e: Exception) {
                listening = false
                if (running) {
                    val msg = "${e.javaClass.simpleName}: ${e.message ?: "bind/accept failed"}"
                    lastError.set(msg)
                    AppLog.w(TAG, "Accept loop error ($msg), retrying in 2s")
                    try {
                        Thread.sleep(2000)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            } finally {
                listening = false
                runCatching { serverSocket?.close() }
                serverSocket = null
            }
        }
        listening = false
        AppLog.i(TAG, "Accept loop exited")
    }

    private fun handleConnection(client: Socket) {
        var dvr: Socket? = null
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            client.tcpNoDelay = true
            client.keepAlive = true

            dvr = Socket().apply {
                prepareDvrSocket(this)
                connect(InetSocketAddress(dvrHost, dvrPort), DVR_CONNECT_TIMEOUT_MS)
                soTimeout = SOCKET_TIMEOUT_MS
                tcpNoDelay = true
                keepAlive = true
            }

            val latch = CountDownLatch(2)
            val dvrSocket = dvr

            pool.execute {
                pipe(client.getInputStream(), dvrSocket.getOutputStream())
                runCatching { dvrSocket.shutdownOutput() }
                latch.countDown()
            }
            pool.execute {
                pipe(dvrSocket.getInputStream(), client.getOutputStream())
                runCatching { client.shutdownOutput() }
                latch.countDown()
            }

            latch.await()
        } catch (e: Exception) {
            val msg = "Relay hop failed: ${e.javaClass.simpleName}"
            lastError.set(msg)
            AppLog.w(TAG, msg)
        } finally {
            runCatching { client.close() }
            runCatching { dvr?.close() }
            activeCount.decrementAndGet()
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buf = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
            // Normal on peer close / half-close.
        }
    }

    fun stop() {
        running = false
        listening = false
        runCatching { serverSocket?.close() }
        pool.shutdownNow()
        AppLog.i(TAG, "Stopped")
    }

    fun isRunning(): Boolean = running

    /** True only while the ServerSocket is bound and accepting. */
    fun isListening(): Boolean = listening

    fun dvrHost(): String = dvrHost

    fun dvrPort(): Int = dvrPort

    fun listenPort(): Int = listenPort

    fun lastError(): String? = lastError.get()

    /** Same shape as the Termux socat command this replaces. */
    fun socatEquivalent(): String =
        "TCP-LISTEN:$listenPort,fork,reuseaddr → TCP:$dvrHost:$dvrPort"

    fun activeConnections(): Int = activeCount.get()

    fun totalConnections(): Long = totalConnections.get()
}
