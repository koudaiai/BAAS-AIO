package moe.baas.aio

import android.annotation.SuppressLint

import android.view.Surface
import android.view.MotionEvent
import moe.baas.aio.companion.CompanionUserService
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import java.io.File

/** AIO owns its Companion runtime inside this Shizuku root/ADB process. */
class SocketBroker : ISocketBroker.Stub() {
    companion object {
        const val SERVICE_VERSION = 46
        fun serviceArgs(context: android.content.Context) =
            rikka.shizuku.Shizuku.UserServiceArgs(android.content.ComponentName(context, SocketBroker::class.java))
                .daemon(false).processNameSuffix("aio-broker").debuggable(BuildConfig.DEBUG)
                .version(SERVICE_VERSION).tag("baas-aio-broker")
        /** Companion channel name; [CompanionBridge] connects to it directly. */
        const val COMPANION_SOCKET = "baas-aio-companion-v1"
    }
    private var service: CompanionUserService? = null
    @Volatile private var alignedLevel: String? = null
    @Volatile private var managedScreenHidden = false
    @Volatile private var screenKeepAlive = false
    private val screenHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val screenPulse = object : Runnable {
        override fun run() {
            if (!screenKeepAlive) return
            try { managedActivityPulse() }
            catch (error: Exception) {
                android.util.Log.e("BaasRuntime", "Cannot keep the physical display awake", error)
                try { endManagedScreen(false) } catch (_: Exception) { }
                return
            }
            if (screenKeepAlive) screenHandler.postDelayed(this, 4000)
        }
    }
    private var clientOwner: android.os.IBinder? = null
    private var clientDeath: android.os.IBinder.DeathRecipient? = null

    init {
        // The companion listener must be created by an aligned thread: binder threads are spawned by
        // Shizuku before this service initializes and keep the unaligned "u:r:su:s0" level. When the
        // app level is not readable yet, leave creation to the first (aligned) call instead.
        val level = alignCurrentThread()
        android.util.Log.i("BaasRuntime", "Broker init MLS level=$level")
        if (level != null) runCatching { companion() }.onFailure {
            android.util.Log.e("BaasRuntime", "Cannot pre-create the companion socket", it)
        }
    }

    /**
     * SELinux 的 mlsconstrain 要求 unix socket 双方 MLS level 相等。
     *
     * app 进程是 u:r:untrusted_app:s0:cXXX,...（带 category），而 broker 由 Shizuku
     * 以 root 拉起，context 是 u:r:su:s0（无 category）。这里把 broker 的 MLS level
     * 对齐到 app 的 category，domain 仍是 su —— root 能力全部保留，只是 level 与 app 一致。
     *
     * 关键点：MLS level 是**按线程**生效的（/proc/self/attr/current 只改当前线程）。
     * binder 线程在 Shizuku 启动服务时就已创建，永远是未对齐的 u:r:su:s0，所以只在 init
     * 里对齐一次不够：那样的线程建出来的 socket 标签没有 category，SELinux 开启时 app
     * 一读写就被拒（表现为 broker 被 DeadObjectException 干掉）。因此每个要碰 socket 的
     * 线程都必须自己先对齐一次。
     */
    private fun appMlsLevel(): String? {
        alignedLevel?.let { return it }
        return runCatching {
            val appPid = ProcessBuilder("pidof", "moe.baas.aio")
                .redirectErrorStream(true).start()
                .inputStream.bufferedReader().use { it.readText() }
                .trim().split(" ").firstOrNull() ?: return@runCatching null
            val categories = File("/proc/$appPid/attr/current").readText().trim().substringAfter("s0:", "")
            if (categories.isBlank()) null else ("u:r:su:s0:" + categories).also { alignedLevel = it }
        }.getOrNull()
    }

    /** Aligns the calling thread and returns the level in effect, or null when it cannot be read. */
    private fun alignCurrentThread(): String? {
        val level = appMlsLevel() ?: return null
        return runCatching {
            File("/proc/self/attr/current").writeText(level)
            level
        }.onFailure {
            android.util.Log.e("BaasRuntime", "Align MLS level failed: $level", it)
        }.getOrNull()
    }

    @Synchronized override fun attachClient(owner: android.os.IBinder) {
        if (clientOwner == owner) return
        val death = android.os.IBinder.DeathRecipient {
            synchronized(this) { if (clientOwner == owner) shutdown() }
        }
        owner.linkToDeath(death, 0)
        clientDeath?.let { previous ->
            try { clientOwner?.unlinkToDeath(previous, 0) } catch (_: java.util.NoSuchElementException) { }
        }
        clientOwner = owner
        clientDeath = death
    }

    @Synchronized private fun companion(): CompanionUserService =
        service ?: CompanionUserService().also { service = it }

    @Synchronized private fun shutdown(): Nothing {
        android.util.Log.e("BaasRuntime", "Runtime shutdown: client owner died or destroy requested; display=${service?.displayId}")
        val runtime = service
        try {
            runtime?.launchedGamePackages()?.forEach { name ->
                try { stopGame(name) } catch (error: Exception) {
                    android.util.Log.e("BaasRuntime", "Cannot stop $name during shutdown", error)
                }
            }
        } finally {
            try {
                try { endManagedScreen(false) } finally { runtime?.close() }
            } finally { kotlin.system.exitProcess(0) }
        }
    }

    @Synchronized override fun preparePreview(createIfMissing: Boolean): String {
        val service = companion()
        val state = org.json.JSONObject(service.stateJson)
        if (!state.getBoolean("alive") && createIfMissing) {
            if (BuildConfig.DEBUG) android.util.Log.d("BaasRuntime", "Create display for preview")
            check(service.startVirtualDisplay(1280, 720, 240) >= 0) { "Cannot create preview display" }
            val activity = "moe.baas.aio.PreviewIdleActivity"
            check(service.launchApp(BuildConfig.APPLICATION_ID, activity)) { "Cannot launch preview target" }
        }
        val reply = org.json.JSONObject(service.stateJson)
        val packageName = if (reply.optBoolean("alive"))
            runCatching { currentPreviewPackage(reply.optInt("displayId", -1)) }.getOrDefault("") else ""
        return reply.put("gameRunning", packageName in listOf("com.YostarJP.BlueArchive", "com.nexon.bluearchive", "com.RoamingStar.BlueArchive", "com.RoamingStar.BlueArchive.bilibili")).toString()
    }

    @Synchronized override fun launchPreviewGame(packageName: String): String {
        if (BuildConfig.DEBUG) android.util.Log.d("BaasRuntime", "Launch preview game package=$packageName display=${service?.displayId}")
        require(packageName in listOf("com.YostarJP.BlueArchive", "com.nexon.bluearchive", "com.RoamingStar.BlueArchive", "com.RoamingStar.BlueArchive.bilibili"))
        val service = companion()
        if (!org.json.JSONObject(service.stateJson).getBoolean("alive")) {
            check(service.startVirtualDisplay(1280, 720, 240) >= 0) { "Cannot create game preview display" }
        }
        check(service.launchApp(packageName, null)) { "Cannot launch $packageName; check game installation" }
        // Disable repeated launches immediately; subsequent refreshes check the actual game.
        return org.json.JSONObject(service.stateJson).put("gameRunning", true).toString()
    }

    @Synchronized override fun closePreviewGame(): String {
        if (BuildConfig.DEBUG) android.util.Log.d("BaasRuntime", "Close game/display requested display=${service?.displayId}", Throwable("Lifecycle caller"))
        val runtime = companion()
        runtime.launchedGamePackages().forEach { stopGame(it) }
        runtime.stopVirtualDisplay()
        return runtime.stateJson
    }

    override fun debugRuntimeState(includeFocus: Boolean): String {
        if (!BuildConfig.DEBUG) return "{}"
        val context = moe.baas.aio.companion.SystemAccess.context()
        val state = org.json.JSONObject(companion().stateJson)
            .put("interactive", context.getSystemService(android.os.PowerManager::class.java).isInteractive)
            .put("keyguardLocked", context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            .put("managedPanelHidden", managedScreenHidden)
            .put("runtimeUid", android.os.Process.myUid())
        val systemDisplay = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(state.optInt("displayId", -1))
        state.put("systemDisplayPresent", systemDisplay != null).put("systemDisplayState", systemDisplay?.state)
        val gamePids = org.json.JSONObject()
        for (name in listOf("com.YostarJP.BlueArchive", "com.nexon.bluearchive", "com.RoamingStar.BlueArchive", "com.RoamingStar.BlueArchive.bilibili")) {
            runCatching {
                val process = ProcessBuilder("pidof", name).redirectErrorStream(true).start()
                if (process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    gamePids.put(name, process.inputStream.bufferedReader().use { it.readText().trim() })
                } else { process.destroy(); gamePids.put(name, "timeout") }
            }.onFailure { gamePids.put(name, "unavailable: ${it.message}") }
        }
        state.put("gamePids", gamePids)
        runCatching { state.put("currentPackage", currentPreviewPackage(state.optInt("displayId", -1))) }
            .onFailure { state.put("packageError", it.toString()) }
        if (includeFocus) runCatching {
            val process = ProcessBuilder("dumpsys", "-t", "1", "input").redirectErrorStream(true).start()
            val dump = process.inputStream.bufferedReader().use { it.readText() }; process.waitFor()
            val lines = dump.lines()
            state.put("inputFocus", lines.flatMapIndexed { index, line ->
                if (line.trim().startsWith("FocusedWindows:") || line.trim().startsWith("FocusedApplications:"))
                    lines.subList(index, minOf(index + 5, lines.size)) else emptyList()
            }.joinToString("\n").take(6000))

        }.onFailure { state.put("focusError", it.toString()) }
        return state.toString()
    }

    override fun awaitGameInput(packageName: String) {
        require(packageName in listOf("com.YostarJP.BlueArchive", "com.nexon.bluearchive", "com.RoamingStar.BlueArchive", "com.RoamingStar.BlueArchive.bilibili"))
        val displayId = companion().displayId
        check(displayId >= 0) { "No virtual display for game input" }
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            check(companion().displayId == displayId) { "Display changed while waiting for game input" }
            val process = ProcessBuilder("dumpsys", "-t", "1", "input").redirectErrorStream(true).start()
            val dump = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            var focusedWindows = false
            for (line in dump.lineSequence()) {
                val value = line.trim()
                if (value.startsWith("FocusedWindows:")) focusedWindows = !value.endsWith("<none>")
                else if (focusedWindows && value.startsWith("displayId=")) {
                    if (value.startsWith("displayId=$displayId,") && value.contains("$packageName/")) return
                } else focusedWindows = false
            }
            Thread.sleep(100)
        }
        error("Game input window did not become ready on display $displayId: $packageName")
    }

    @Synchronized override fun beginManagedScreen() {
        // The power key can restore the panel without updating our session state.
        check(companion().setPhysicalScreenOff(true)) { "Cannot turn off the physical panel" }
        managedScreenHidden = true
        screenKeepAlive = true
        screenHandler.removeCallbacks(screenPulse)
        screenHandler.post(screenPulse)
    }

    @Synchronized override fun endManagedScreen(sleep: Boolean) {
        if (!sleep && !managedScreenHidden) return
        val runtime = companion()
        if (sleep) {
            // Sleep the primary display, independently of the virtual display's lifetime.
            val power = moe.baas.aio.companion.SystemAccess.context()
                .getSystemService(android.os.PowerManager::class.java)
            if (power.isInteractive) {
                val injected = moe.baas.aio.companion.SystemAccess.keyEvent(0, android.view.KeyEvent.KEYCODE_SLEEP)
                if (!injected) {
                    // Injection can be dropped while the display powers down; shell fallback is equivalent.
                    runCatching {
                        ProcessBuilder("input", "keyevent", "223").redirectErrorStream(true).start()
                            .waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }
                val deadline = android.os.SystemClock.elapsedRealtime() + 3000
                while (power.isInteractive && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
                check(!power.isInteractive) { "Device did not enter sleep" }
            }
        } else {
            check(runtime.setPhysicalScreenOff(false)) { "Cannot restore the physical panel" }
        }
        managedScreenHidden = false
        screenKeepAlive = false
        screenHandler.removeCallbacks(screenPulse)
    }

    @Synchronized override fun finishManagedScreen() {
        // Leave panel and lock state to the user; allow normal idle timeout again.
        screenKeepAlive = false
        screenHandler.removeCallbacks(screenPulse)
    }

    /**
     * End of a managed batch: hand the device back locked.
     *
     * While the batch ran, the physical panel was blanked through SurfaceControl so the virtual
     * display kept composing; that leaves the system interactive with a dark screen. A real sleep
     * returns the keyguard instead of that half-awake state, so the next batch wakes and unlocks
     * like any other cold start.
     */
    @Synchronized override fun lockManagedScreen(): Boolean {
        screenKeepAlive = false
        screenHandler.removeCallbacks(screenPulse)
        if (!managedScreenHidden) return true   // Nothing was blanked here; the panel is the user's.
        return try {
            endManagedScreen(true)
            DebugTrace.log("Managed batch end: device locked")
            true
        } catch (error: Exception) {
            android.util.Log.e("BaasRuntime", "Cannot lock after the managed batch; restoring the panel", error)
            DebugTrace.log("Managed batch end: lock failed (${error.message}); restoring the panel", 2)
            // Never leave the user with a dark screen that no longer reacts to the task.
            runCatching { endManagedScreen(false) }
            false
        }
    }

    override fun managedActivityPulse() {
        moe.baas.aio.companion.SystemAccess.userActivity(0)
    }

    /** Types the PIN into the keyguard bouncer. The PIN itself is never logged or echoed. */
    override fun unlockDevice(pin: String): Boolean {
        val started = android.os.SystemClock.elapsedRealtime()
        val ok = companion().unlock(pin)
        DebugTrace.log("Unlock attempt digits=${pin.length} ok=$ok elapsed=${android.os.SystemClock.elapsedRealtime() - started}ms")
        return ok
    }

    override fun gameOptions(packageName: String): String {
        require(packageName == "com.nexon.bluearchive")
        val options = File("/sdcard/Android/data/$packageName/files/DeviceOption")
        check(options.isFile) { "Global game language settings missing; launch the game and select its language first" }
        return options.readText()
    }

    override fun writeLocalizeConfig(packageName: String, content: String) {
        require(packageName in listOf("com.RoamingStar.BlueArchive", "com.RoamingStar.BlueArchive.bilibili")) {
            "Anti-censorship is only supported for CN and Bilibili"
        }
        require(content.toByteArray(Charsets.UTF_8).size in 1..4096 &&
            content.lineSequence().any { it.trim() == "IsLocalize=false" }) { "Invalid LocalizeConfig" }
        val directory = File("/sdcard/Android/data/$packageName/files")
        check(directory.isDirectory) { "请先启动一次对应游戏，再使用一键反和谐" }
        // Shizuku supplies root/shell access to the game's external data directory.
        val temporary = File(directory, "LocalizeConfig.txt.baas-tmp")
        try {
            temporary.writeText(content, Charsets.UTF_8)
            check(temporary.readText(Charsets.UTF_8) == content) { "LocalizeConfig verification failed" }
            check(temporary.renameTo(File(directory, "LocalizeConfig.txt"))) { "Cannot replace LocalizeConfig" }
        } finally {
            temporary.delete()
        }
    }

    override fun stopGame(packageName: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("BaasRuntime", "Force-stop game package=$packageName display=${service?.displayId}", Throwable("Lifecycle caller"))
        require(packageName in listOf("com.YostarJP.BlueArchive", "com.nexon.bluearchive", "com.RoamingStar.BlueArchive", "com.RoamingStar.BlueArchive.bilibili"))
        val manager = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null)
        Class.forName("android.app.IActivityManager").getMethod("forceStopPackage", String::class.java, Int::class.javaPrimitiveType)
            .invoke(manager, packageName, 0)
        service?.forgetGame(packageName)
    }

    override fun setPreviewSurface(surface: Surface?, owner: android.os.IBinder?) {
        if (BuildConfig.DEBUG) android.util.Log.d("BaasRuntime", "Preview surface ${if (surface == null) "detach" else "attach"} valid=${surface?.isValid} owner=$owner ownerAlive=${owner?.isBinderAlive} display=${service?.displayId}")
        // Direct calls transfer ownership to the runtime, which releases this Parcel copy.
        companion().setPreviewSurface(surface, owner)
    }
    override fun touch(event: MotionEvent): Boolean = companion().touch(event)

    // Runs in the Shizuku root/ADB process, never the application's UID.
    @SuppressLint("BlockedPrivateApi")
    override fun currentPreviewPackage(displayId: Int): String {
        if (displayId < 0) return ""
        val manager = Class.forName("android.app.ActivityTaskManager").getDeclaredMethod("getService").invoke(null)
        val tasks = runningTasks(manager, displayId)
        return tasks.filterIsInstance<android.app.ActivityManager.RunningTaskInfo>()
            .firstOrNull { task ->
                try {
                    task.javaClass.getField("displayId").getInt(task) == displayId
                } catch (_: ReflectiveOperationException) {
                    displayId == 0
                }
            }?.topActivity?.packageName.orEmpty()
    }

    /** IActivityTaskManager.getTasks grew parameters across API levels; pick a matching overload. */
    private fun runningTasks(manager: Any, displayId: Int): List<*> {
        val methods = Class.forName("android.app.IActivityTaskManager").methods.filter { it.name == "getTasks" }
        val four = methods.firstOrNull { it.parameterTypes.size == 4 }
        if (four != null) return four.invoke(manager, 20, false, false, displayId) as List<*>
        val three = methods.firstOrNull { it.parameterTypes.size == 3 }
        if (three != null) {
            val types = three.parameterTypes
            return if (types[2] == Integer.TYPE) three.invoke(manager, 20, false, displayId) as List<*>
            else three.invoke(manager, 20, false, false) as List<*>
        }
        val two = methods.firstOrNull { it.parameterTypes.size == 2 }
        if (two != null) return two.invoke(manager, 20, false) as List<*>
        val one = methods.firstOrNull { it.parameterTypes.size == 1 }
            ?: error("IActivityTaskManager.getTasks not found")
        return one.invoke(manager, 20) as List<*>
    }

    private fun connectSocket(): ParcelFileDescriptor =
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(COMPANION_SOCKET, LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = 20000
            Os.setsockoptTimeval(socket.fileDescriptor, OsConstants.SOL_SOCKET,
                OsConstants.SO_SNDTIMEO, StructTimeval.fromMillis(20000))
            ParcelFileDescriptor.dup(socket.fileDescriptor)
        }

    override fun openCompanionSocket(): ParcelFileDescriptor = try {
        // Binder threads keep the unaligned level, and the client socket inherits the label of the
        // thread that creates it; align here so the handed-over fd carries the app's categories.
        alignCurrentThread()
        companion()
        connectSocket()
    } catch (error: Exception) {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        // binder 会杀掉抛出 RuntimeException 的服务端，客户端只看到 DeadObjectException。
        // 这里必须先把真实原因写进 logcat，否则故障信息会彻底丢失。
        android.util.Log.e("BaasRuntime", "openCompanionSocket failed: " + cause.javaClass.name + ": " + cause.message, cause)
        cause.printStackTrace()
        throw IllegalStateException("AIO device runtime failed: ${cause.message ?: cause.javaClass.simpleName}; check Shizuku permission", cause)
    }

    /** Brings the companion (and its listener socket) up on an aligned thread before a direct connect. */
    override fun ensureCompanion() {
        alignCurrentThread()
        companion()
    }

    override fun ping() = "uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()} runtime=embedded"

    // Fixed debug target, used to verify actual Android-delivered events without ADB.
    override fun readProbeState(): String = try {
        check(BuildConfig.DEBUG) { "Probe state is only available in debug builds" }
        File("/data/user/0/moe.baas.aio/files/probe_state.json").readText()
    } catch (error: Exception) { throw IllegalStateException("Probe state unavailable", error) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == 16777115) { shutdown() }
        return super.onTransact(code, data, reply, flags)
    }
}
