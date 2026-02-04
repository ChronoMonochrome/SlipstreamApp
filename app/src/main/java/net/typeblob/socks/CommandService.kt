package net.typeblob.socks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CommandResult(val exitCode: Int, val output: String)

class CommandService : LifecycleService(), CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO

    private val TAG = "CommandService"
    private val NOTIFICATION_CHANNEL_ID = "CommandServiceChannel"
    private val NOTIFICATION_ID = 101

    private val mainHandler = Handler(Looper.getMainLooper())

    private var slipstreamProcess: Process? = null
    private var slipstreamReaderJob: Job? = null
    private var slipstreamErrorJob: Job? = null
    private var tunnelMonitorJob: Job? = null
    private var mainExecutionJob: Job? = null

    private val tunnelMutex = Mutex()

    private var resolversConfig: ArrayList<String> = arrayListOf()
    private var domainNameConfig: String = ""
    private var socks5Port: String = "1080"
    private var isRestarting = false

    companion object {
        const val EXTRA_RESOLVERS = "extra_ip_addresses_list"
        const val EXTRA_DOMAIN = "domain_name"
        const val EXTRA_SOCKS5_PORT = "socks5_port"
        const val SLIPSTREAM_BINARY_NAME = "libslipstream.so"
        const val ACTION_STATUS_UPDATE = "net.typeblob.socks.STATUS_UPDATE"
        const val ACTION_ERROR = "net.typeblob.socks.ERROR"
        const val ACTION_REQUEST_STATUS = "net.typeblob.socks.REQUEST_STATUS"
        const val ACTION_LOG = "net.typeblob.socks.LOG"
        const val EXTRA_STATUS_SLIPSTREAM = "status_slipstream"
        const val EXTRA_STATUS_SOCKS5 = "status_socks5"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_ERROR_OUTPUT = "error_output"
        const val EXTRA_LOG_MESSAGE = "log_message"
        const val EXTRA_LOG_IS_ERROR = "log_is_error"
        private const val MONITOR_INTERVAL_MS = 2000L
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_REQUEST_STATUS) {
            sendCurrentStatus("Request")
            return START_STICKY
        }

        val newResolvers = intent?.getStringArrayListExtra(EXTRA_RESOLVERS) ?: arrayListOf()
        val newDomain = intent?.getStringExtra(EXTRA_DOMAIN) ?: ""
        val newSocks5Port = intent?.getStringExtra(EXTRA_SOCKS5_PORT) ?: "1080"

        if (newResolvers == resolversConfig &&
                        newDomain == domainNameConfig &&
                        newSocks5Port == socks5Port &&
                        slipstreamProcess?.isAlive == true
        ) {
            broadcastLog("Profile unchanged and alive. Skipping.")
            return START_STICKY
        }

        resolversConfig = newResolvers
        domainNameConfig = newDomain
        socks5Port = newSocks5Port

        broadcastLog("Service starting - Domain: $domainNameConfig, Port: $socks5Port")

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        cleanUpLingeringProcesses()
        sendStatusUpdate("Cleaning up...", "Waiting...")

        mainExecutionJob?.cancel()
        mainExecutionJob =
                launch {
                    try {
                        delay(1000)
                        stopTunnel()
                        delay(500)
                        startTunnel()
                    } catch (e: CancellationException) {
                        broadcastLog("Main execution cancelled")
                    } catch (e: Exception) {
                        handleError("Setup failed", e.message ?: "Unknown error")
                    }
                }

        return START_STICKY
    }

    private suspend fun startTunnel() =
            tunnelMutex.withLock {
                if (slipstreamProcess?.isAlive == true) {
                    broadcastLog("Tunnel already running")
                    return
                }

                try {
                    // Use the native library directory where Android allows execution
                    val nativeLibDir = applicationInfo.nativeLibraryDir
                    val slipstreamPath = File(nativeLibDir, SLIPSTREAM_BINARY_NAME).absolutePath
                    
                    broadcastLog("Step 1: Native lib dir: $nativeLibDir")
                    broadcastLog("Step 2: Looking for binary: $slipstreamPath")
                    
                    val slipstreamFile = File(slipstreamPath)
                    
                    if (!slipstreamFile.exists()) {
                        broadcastLog("Binary not found in native lib dir!", isError = true)
                        broadcastLog("Please move binary to app/src/main/jniLibs/arm64-v8a/libslipstream.so", isError = true)
                        handleError("Binary not found", "Rebuild APK with binary in jniLibs folder")
                        return
                    }
                    
                    broadcastLog("Step 3: Binary exists: ${slipstreamFile.exists()}")
                    broadcastLog("Step 4: Binary size: ${slipstreamFile.length()} bytes")
                    broadcastLog("Step 5: Binary executable: ${slipstreamFile.canExecute()}")

                    val commandList = mutableListOf(slipstreamPath, domainNameConfig)
                    resolversConfig.forEach { commandList.add(it) }
                    commandList.add("--socks-port")
                    commandList.add(socks5Port)

                    broadcastLog("Step 6: Command: ${commandList.joinToString(" ")}")
                    broadcastLog("Step 7: Starting process from native lib dir...")

                    // Keep stdout and stderr separate to capture crash info
                    val processBuilder = ProcessBuilder(commandList)
                    processBuilder.redirectErrorStream(false)
                    slipstreamProcess = processBuilder.start()

                    broadcastLog("Step 8: Process started, alive: ${slipstreamProcess?.isAlive}")

                    // Read stdout
                    slipstreamReaderJob =
                            launch {
                                val reader = BufferedReader(InputStreamReader(slipstreamProcess?.inputStream))
                                try {
                                    var lineCount = 0
                                    reader.forEachLine { line ->
                                        lineCount++
                                        broadcastLog("[stdout:$lineCount] $line")
                                        if (line.contains(
                                                        "ListenerBind_Init failed",
                                                        ignoreCase = true
                                                ) || line.contains("error", ignoreCase = true)
                                        ) {
                                            launch { handleError("Slipstream error", line) }
                                        }
                                        if (line.contains("SOCKS", ignoreCase = true) && 
                                            line.contains("listening", ignoreCase = true)) {
                                            sendStatusUpdate("Running", "Running on port $socks5Port")
                                        }
                                    }
                                    broadcastLog("stdout closed after $lineCount lines")
                                } catch (e: Exception) {
                                    broadcastLog("stdout error: ${e.message}", isError = true)
                                }
                            }

                    // Read stderr (shows crash info and missing libraries)
                    slipstreamErrorJob =
                            launch {
                                val errorReader = BufferedReader(InputStreamReader(slipstreamProcess?.errorStream))
                                try {
                                    var errLineCount = 0
                                    errorReader.forEachLine { line ->
                                        errLineCount++
                                        broadcastLog("[stderr:$errLineCount] $line", isError = true)
                                    }
                                    if (errLineCount > 0) {
                                        broadcastLog("stderr closed after $errLineCount lines", isError = true)
                                    }
                                } catch (e: Exception) {
                                    broadcastLog("stderr reader error: ${e.message}", isError = true)
                                }
                            }

                    delay(1500)
                    val isAliveAfterDelay = slipstreamProcess?.isAlive ?: false
                    broadcastLog("Step 9: After delay, alive: $isAliveAfterDelay")
                    
                    if (!isAliveAfterDelay) {
                        val exitValue = try {
                            slipstreamProcess?.exitValue()
                        } catch (e: Exception) {
                            "unknown"
                        }
                        broadcastLog("Process died! Exit code: $exitValue", isError = true)
                        
                        // Wait a bit for stderr to be read
                        delay(500)
                        
                        handleError("Slipstream failed", "Process died. Exit code: $exitValue")
                        return
                    }

                    sendStatusUpdate("Running", "Running on port $socks5Port")
                    broadcastLog("Step 10: Starting monitoring")
                    startMonitoring()
                } catch (e: Exception) {
                    broadcastLog("Error: ${e.javaClass.simpleName}: ${e.message}", isError = true)
                    e.printStackTrace()
                    handleError("Slipstream failed", "${e.javaClass.simpleName}: ${e.message}")
                }
            }

    private suspend fun stopTunnel() =
            tunnelMutex.withLock {
                try {
                    broadcastLog("Stopping tunnel...")
                    sendStatusUpdate("Stopping...", "Stopping...")
                    tunnelMonitorJob?.cancel()
                    slipstreamReaderJob?.cancel()
                    slipstreamErrorJob?.cancel()

                    slipstreamProcess?.destroy()
                    slipstreamProcess?.waitFor()
                    slipstreamProcess = null

                    sendStatusUpdate("Stopped", "Stopped")
                    broadcastLog("Tunnel stopped")
                } catch (e: Exception) {
                    broadcastLog("Error stopping: ${e.message}", isError = true)
                }
            }

    private fun startMonitoring() {
        tunnelMonitorJob?.cancel()
        tunnelMonitorJob =
                launch {
                    try {
                        while (isActive) {
                            delay(MONITOR_INTERVAL_MS)
                            if (slipstreamProcess?.isAlive == false) {
                                broadcastLog("Process died unexpectedly!", isError = true)
                                handleError("Slipstream crashed", "Process terminated unexpectedly")
                                break
                            }
                        }
                    } catch (e: CancellationException) {
                        broadcastLog("Monitor cancelled")
                    }
                }
    }

    private fun handleError(message: String, detail: String) {
        broadcastLog("ERROR: $message - $detail", isError = true)
        sendStatusUpdate("Failed: $message", "Stopped")
        val intent =
                Intent(ACTION_ERROR).apply {
                    putExtra(EXTRA_ERROR_MESSAGE, message)
                    putExtra(EXTRA_ERROR_OUTPUT, detail)
                }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        launch { stopTunnel() }
    }

    private fun sendCurrentStatus(reason: String) {
        val slipStatus =
                if (slipstreamProcess?.isAlive == true) "Running" else "Stopped"
        val socksStatus = 
                if (slipstreamProcess?.isAlive == true) "Running on port $socks5Port" else "Stopped"
        broadcastLog("Status ($reason): Slip=$slipStatus, SOCKS5=$socksStatus")
        sendStatusUpdate(slipStatus, socksStatus)
    }

    override fun onDestroy() {
        broadcastLog("Service destroying")
        job.cancel()
        runBlocking { stopTunnel() }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                    NotificationChannel(
                                    NOTIFICATION_CHANNEL_ID,
                                    "Command Service",
                                    NotificationManager.IMPORTANCE_LOW
                            )
                            .apply { description = "Background command execution" }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("SlipstreamApp")
                .setContentText("SOCKS5 proxy running on port $socks5Port")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .build()
    }

    private fun sendStatusUpdate(slipstreamStatus: String, socksStatus: String) {
        val intent =
                Intent(ACTION_STATUS_UPDATE).apply {
                    putExtra(EXTRA_STATUS_SLIPSTREAM, slipstreamStatus)
                    putExtra(EXTRA_STATUS_SOCKS5, socksStatus)
                }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastLog(message: String, isError: Boolean = false) {
        Log.d(TAG, message)
        val intent = Intent(ACTION_LOG).apply {
            putExtra(EXTRA_LOG_MESSAGE, message)
            putExtra(EXTRA_LOG_IS_ERROR, isError)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun cleanUpLingeringProcesses() {
        try {
            broadcastLog("Cleaning up old processes...")
            val killProcess = Runtime.getRuntime().exec(arrayOf("killall", "-9", "libslipstream.so"))
            val exitCode = killProcess.waitFor()
            broadcastLog("Cleanup exit: $exitCode")
        } catch (e: Exception) {
            broadcastLog("Cleanup: ${e.message}")
        }
    }
}