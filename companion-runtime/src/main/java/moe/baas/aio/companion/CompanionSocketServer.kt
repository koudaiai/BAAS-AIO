package moe.baas.aio.companion

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import kotlin.concurrent.thread

/** Protocol v1: u32 big-endian JSON length, JSON, then payloadBytes binary bytes. */
class CompanionSocketServer(private val service: CompanionUserService) : AutoCloseable {
    private val listener = LocalServerSocket("baas-aio-companion-v1")
    private val clients = ConcurrentHashMap.newKeySet<AutoCloseable>()
    private val slots = Semaphore(4)
    @Volatile private var closed = false
    /** The app may connect directly, so its traffic never needs a descriptor handed over by Binder. */
    private val appUid by lazy {
        runCatching { SystemAccess.context().packageManager.getApplicationInfo("moe.baas.aio", 0).uid }
            .getOrDefault(-1)
    }

    init { thread(name = "baas-socket-accept", isDaemon = true) { acceptLoop() } }

    /**
     * Serves a channel whose peer end the app created itself (socketpair) and handed over through
     * Binder. Both ends then carry the app's SELinux label, so no `untrusted_app -> su` socket
     * permission is needed and nothing privileged has to be relabelled.
     */
    fun attach(descriptor: ParcelFileDescriptor) {
        if (closed || !slots.tryAcquire(500, TimeUnit.MILLISECONDS)) {
            descriptor.close(); return
        }
        clients.add(descriptor)
        thread(name = "baas-socket-pair", isDaemon = true) {
            try {
                // Separate stream wrappers over one descriptor; the descriptor is closed once below.
                val input = DataInputStream(FileInputStream(descriptor.fileDescriptor))
                val output = DataOutputStream(FileOutputStream(descriptor.fileDescriptor))
                pump(input, output)
            } catch (e: Exception) {
                if (!closed) Log.d("BaasSocket", "Pair channel ended: $e")
            } finally {
                runCatching { descriptor.close() }
                clients.remove(descriptor)
                slots.release()
                Log.d("BaasSocket", "Released pair channel active=${clients.size}")
            }
        }
    }

    private fun acceptLoop() {
        while (!closed) {
            try {
                val socket = listener.accept()
                val uid = socket.peerCredentials.uid
                // Only the app itself, ADB/root may reach privileged operations.
                // Closed channels release their slots on the serving threads, not on the caller.
                if ((uid != 0 && uid != 2000 && uid != appUid) || !slots.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                    Log.d("BaasSocket", "Rejected client uid=$uid active=${clients.size}")
                    socket.close()
                    continue
                }
                clients.add(socket)
                Log.d("BaasSocket", "Accepted client active=${clients.size}")
                thread(name = "baas-socket-client", isDaemon = true) { serve(socket) }
            } catch (e: IOException) {
                if (!closed) Log.e("BaasSocket", "Accept failed", e)
            }
        }
    }

    private fun serve(socket: LocalSocket) {
        try {
            socket.use {
                DataInputStream(socket.inputStream).use { input ->
                    DataOutputStream(socket.outputStream).use { output ->
                        pump(input, output) { socket.soTimeout = it }
                    }
                }
            }
        } catch (e: Exception) {
            if (!closed) Log.d("BaasSocket", "Client disconnected: $e")
        } finally {
            clients.remove(socket)
            slots.release()
            Log.d("BaasSocket", "Released client active=${clients.size}")
        }
    }

    /** Framing loop shared by the listener and the app-provided descriptor. */
    private fun pump(input: DataInputStream, output: DataOutputStream, setSoTimeout: (Int) -> Unit = {}) {
        while (!closed) {
            // Idle input channels can remain unused throughout a battle.
            setSoTimeout(0)
            val first = input.read()
            if (first == -1) break
            setSoTimeout(30_000)
            val length = try {
                (first shl 24) or (input.readUnsignedByte() shl 16) or
                    (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
            } catch (_: EOFException) { break }
            if (length !in 1..65536) throw IOException("Invalid request length")
            val body = ByteArray(length)
            input.readFully(body)
            val request = JSONObject(String(body, Charsets.UTF_8))
            var payload = ByteArray(0)
            val response = try {
                require(request.optInt("apiVersion", 1) == 1) { "Unsupported API version" }
                if (request.optString("cmd") == "screenshot") {
                    val frame = service.captureFrame()
                    payload = frame.png
                    frame.metadata().put("ok", true).put("cmd", "screenshot")
                        .put("requestId", request.optString("requestId", "")).put("apiVersion", 1)
                } else CompanionCommands.execute(service, request)
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.toString())
                    .put("cmd", request.optString("cmd")).put("apiVersion", 1)
                    .put("requestId", request.optString("requestId", ""))
            }
            response.put("payloadBytes", payload.size)
            val json = response.toString().toByteArray(Charsets.UTF_8)
            output.writeInt(json.size)
            output.write(json)
            output.write(payload)
            output.flush()
        }
    }

    override fun close() {
        closed = true
        try { listener.close() } catch (_: IOException) { }
        for (client in clients) runCatching { client.close() }
    }
}
