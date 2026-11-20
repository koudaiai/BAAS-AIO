package moe.baas.aio

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID
import java.io.IOException

data class CompanionFrame(val metadata: String, val png: ByteArray)
class CompanionCommandException(message: String, val replyJson: String = "{}") : RuntimeException(message)

/** Android-local transport: two authorized descriptors, no desktop or ADB dependency. */
class CompanionBridge(private val broker: ISocketBroker) : AutoCloseable {
    private var input = openChannel()
    private var capture = try { openChannel() }
        catch (error: Throwable) { input.close(); throw error }

    // Called by the single workflow worker after resume, before any input is sent.
    fun reconnect() {
        DebugTrace.log("Companion transport reconnect")
        close()
        input = openChannel()
        capture = try { openChannel() } catch (error: Throwable) { input.close(); throw error }
    }

    private fun openChannel(): Channel {
        // Preferred: the app creates the socketpair, so both ends carry this app's SELinux label and
        // nothing privileged has to be handed across the app/broker boundary.
        pairChannel()?.let { return it }
        // Direct connect keeps the socket inside this process: no descriptor crosses the app/broker
        // boundary, which some ROMs reject through SELinux binder checks while enforcing.
        directChannel()?.let { return it }
        var last: Throwable? = null
        repeat(3) {
            val channel = Channel(broker.openCompanionSocket())
            try {
                channel.request(JSONObject().put("cmd", "ping"))
                return channel
            } catch (error: IllegalStateException) {
                channel.close(); last = error
                if (error.cause !is IOException && error.cause !is android.system.ErrnoException) throw error
                Thread.sleep(25)
            }
        }
        throw checkNotNull(last)
    }

    /** Socketpair created here and handed to the broker: no su-labelled socket, no policy change. */
    private fun pairChannel(): Channel? {
        val first = java.io.FileDescriptor()
        val second = java.io.FileDescriptor()
        var appSide: ParcelFileDescriptor? = null
        try {
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0, first, second)
            val local = ParcelFileDescriptor.dup(first)
            appSide = local
            val peer = ParcelFileDescriptor.dup(second)
            val attached = try { broker.attachCompanionSocket(peer) } finally { peer.close() }
            check(attached) { "Broker rejected the channel" }
            for (option in intArrayOf(OsConstants.SO_SNDTIMEO, OsConstants.SO_RCVTIMEO)) {
                runCatching {
                    Os.setsockoptTimeval(local.fileDescriptor, OsConstants.SOL_SOCKET, option,
                        StructTimeval.fromMillis(20_000))
                }
            }
            val channel = Channel(local)
            appSide = null      // Ownership moved to the channel.
            try {
                channel.request(JSONObject().put("cmd", "ping"))
            } catch (error: Throwable) {
                channel.close(); throw error
            }
            return channel
        } catch (error: Throwable) {
            DebugTrace.log("Socketpair channel failed: ${error.message}", 2)
            appSide?.let { runCatching { it.close() } }
            return null
        } finally {
            runCatching { Os.close(first) }
            runCatching { Os.close(second) }
        }
    }

    /** Connects to the broker's companion socket as this app; the broker whitelists this uid. */
    private fun directChannel(): Channel? {
        val socket = LocalSocket()
        return try {
            broker.ensureCompanion()
            socket.connect(LocalSocketAddress(SocketBroker.COMPANION_SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
            val descriptor = ParcelFileDescriptor.dup(socket.fileDescriptor)
            try {
                for (option in intArrayOf(OsConstants.SO_SNDTIMEO, OsConstants.SO_RCVTIMEO)) {
                    Os.setsockoptTimeval(descriptor.fileDescriptor, OsConstants.SOL_SOCKET, option,
                        StructTimeval.fromMillis(20_000))
                }
                Channel(descriptor).also { it.request(JSONObject().put("cmd", "ping")) }
            } catch (error: Throwable) {
                descriptor.close(); throw error
            }
        } catch (error: Throwable) {
            DebugTrace.log("Direct companion connect failed; falling back to the broker channel: ${error.message}", 2)
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    fun command(json: String): String {
        val request = JSONObject(json)
        require(request.optString("cmd") != "screenshot") { "Use screenshot()" }
        val reply = input.request(request).metadata
        if (request.optString("cmd") == "launch") {
            val packageName = request.optString("package")
            if (packageName in listOf("com.YostarJP.BlueArchive", "com.nexon.bluearchive", "com.RoamingStar.BlueArchive", "com.RoamingStar.BlueArchive.bilibili")) {
                val started = android.os.SystemClock.elapsedRealtime()
                DebugTrace.log("Waiting for game input window: $packageName; " + debugState())
                try { broker.awaitGameInput(packageName) }
                catch (error: Exception) {
                    DebugTrace.log("Game window wait failed: $error; " + debugState(true), 3); throw error
                }
                DebugTrace.log("Game input ready after ${android.os.SystemClock.elapsedRealtime() - started}ms: $packageName")
            }
        }
        return reply
    }

    fun screenshot(): CompanionFrame = capture.request(JSONObject().put("cmd", "screenshot"))
    fun debugState(includeFocus: Boolean = false): String =
        if (!BuildConfig.DEBUG) "{}" else try { broker.debugRuntimeState(includeFocus) } catch (error: Exception) { "Diagnostic unavailable: $error" }
    fun beginManagedScreen() {
        DebugTrace.log("Managed screen started; before=" + debugState())
        broker.beginManagedScreen()
        DebugTrace.log("Managed screen ready; after=" + debugState())
    }
    /** Unlock the keyguard with the user's PIN. The PIN is passed through without any echo. */
    fun unlockDevice(pin: String): Boolean {
        require(pin.isNotEmpty()) { "No unlock PIN configured" }
        DebugTrace.log("Keyguard unlock requested digits=${pin.length}")
        return broker.unlockDevice(pin)
    }
    /** End of a managed batch: lock the device instead of leaving a dark but interactive screen. */
    fun lockManagedScreen(): Boolean {
        val locked = broker.lockManagedScreen()
        DebugTrace.log("Managed end-of-batch lock requested; locked=$locked")
        return locked
    }
    fun brokerInfo(): String = broker.ping()
    fun probeState(): String = broker.readProbeState()
    fun gameOptions(packageName: String): String = broker.gameOptions(packageName)
    fun writeLocalizeConfig(packageName: String, content: String) = broker.writeLocalizeConfig(packageName, content)
    fun stopGame(packageName: String) = broker.stopGame(packageName)
    fun currentPackage(): String = broker.currentPreviewPackage(
        JSONObject(command("{\"cmd\":\"status\"}")).getInt("displayId"))
    override fun close() { try { input.close() } finally { capture.close() } }

    private inner class Channel(private val descriptor: ParcelFileDescriptor) : AutoCloseable {
        @Synchronized fun request(request: JSONObject): CompanionFrame {
            val started = android.os.SystemClock.elapsedRealtime()
            val id = UUID.randomUUID().toString()
            val command = request.optString("cmd")
            try {
                request.put("apiVersion", 1).put("requestId", id)
                val body = request.toString().toByteArray(Charsets.UTF_8)
                require(body.size in 1..65536)
                write(ByteBuffer.allocate(4).putInt(body.size).array())
                write(body)
                val size = ByteBuffer.wrap(read(4)).int
                if (size !in 1..65536) throw IOException("Invalid Companion header size")
                val reply = JSONObject(String(read(size), Charsets.UTF_8))
                if (reply.optInt("apiVersion") != 1 || reply.optString("requestId") != id)
                    throw IOException("Companion reply mismatch")
                val payloadSize = reply.optInt("payloadBytes", 0)
                if (payloadSize !in 0..134217728) throw IOException("Invalid Companion payload size")
                val payload = read(payloadSize)
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                if (!reply.optBoolean("ok")) {
                    DebugTrace.log("Request failed id=$id elapsed=${elapsed}ms request=$request reply=$reply; " + debugState(true), 3)
                    throw CompanionCommandException(reply.optString("error", "Companion command failed"), reply.toString())
                }
                if (command !in setOf("screenshot", "status", "ping"))
                    DebugTrace.log("Request completed id=$id elapsed=${elapsed}ms request=$request")
                else if (elapsed >= 1500) DebugTrace.log("Slow $command id=$id elapsed=${elapsed}ms; " + debugState(), 2)
                return CompanionFrame(reply.toString(), payload)
            } catch (error: android.system.ErrnoException) {
                DebugTrace.log("Transport failure cmd=$command id=$id elapsed=${android.os.SystemClock.elapsedRealtime() - started}ms: $error; " + debugState(true), 3)
                close()
                throw IllegalStateException("Companion connection failed; command was not replayed", error)
            } catch (error: IOException) {
                DebugTrace.log("Transport failure cmd=$command id=$id elapsed=${android.os.SystemClock.elapsedRealtime() - started}ms: $error; " + debugState(true), 3)
                close()
                throw IllegalStateException("Companion connection failed; command was not replayed", error)
            } catch (error: org.json.JSONException) {
                DebugTrace.log("Transport failure cmd=$command id=$id elapsed=${android.os.SystemClock.elapsedRealtime() - started}ms: $error; " + debugState(true), 3)
                close()
                throw IllegalStateException("Invalid Companion response; command was not replayed", error)
            }
        }

        private fun read(size: Int): ByteArray {
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = Os.read(descriptor.fileDescriptor, bytes, offset, size - offset)
                if (count <= 0) throw IOException("Companion closed its connection")
                offset += count
            }
            return bytes
        }

        private fun write(bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val count = Os.write(descriptor.fileDescriptor, bytes, offset, bytes.size - offset)
                if (count <= 0) throw IOException("Companion write failed")
                offset += count
            }
        }

        @Synchronized override fun close() { descriptor.close() }
    }
}
