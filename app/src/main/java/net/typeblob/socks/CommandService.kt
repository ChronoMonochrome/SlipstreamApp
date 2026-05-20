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

private var proxyProcess: Process? = null
private var proxyReaderJob: Job? = null
private var tunnelMonitorJob: Job? = null
private var mainExecutionJob: Job? = null

private val tunnelMutex = Mutex()

// Конфигурационные переменные текущей сессии
private var hostConfig: String = ""
private var portConfig: Int = 8000
private var userConfig: String = "chrono"
private var privateKeyPath: String = ""
    private var isRestarting = false

    // Локальный трекинг внутреннего состояния Go-бинарника ("Running", "Reconnecting...", "Stopped")
    private var tunnelState = "Stopped"

    companion object {
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_USER = "extra_user"
        const val EXTRA_DOMAIN = "domain_name"
        const val EXTRA_KEY_PATH = "private_key_path"
        const val EXTRA_UPSTREAM_PORT = "extra_upstream_port"
        const val EXTRA_LOCAL_PROXY_PORT = "extra_local_proxy_port"

        const val PROXY_CLIENT_BINARY_NAME = "proxy-client"

        const val ACTION_STATUS_UPDATE = "net.typeblob.socks.STATUS_UPDATE"
        const val ACTION_ERROR = "net.typeblob.socks.ERROR"
        const val ACTION_REQUEST_STATUS = "net.typeblob.socks.REQUEST_STATUS"
        const val ACTION_LOG_OUTPUT = "net.typeblob.socks.LOG_OUTPUT"

        const val EXTRA_STATUS_SLIPSTREAM = "status_slipstream"
        const val EXTRA_STATUS_SSH = "status_ssh"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_LOG_LINE = "log_line"

        private const val MONITOR_INTERVAL_MS = 2000L
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_REQUEST_STATUS) {
            sendCurrentStatus()
            return START_STICKY
        }

        val newHost = intent?.getStringExtra(EXTRA_HOST) ?: intent?.getStringExtra(EXTRA_DOMAIN) ?: ""

        val newPort = intent?.let {
            if (it.hasExtra(EXTRA_PORT)) {
                try {
                    it.getIntExtra(EXTRA_PORT, 8000)
                } catch (e: Exception) {
                    it.getStringExtra(EXTRA_PORT)?.toIntOrNull() ?: 8000
                }
            } else 8000
        } ?: 8000

        val newUser = intent?.getStringExtra(EXTRA_USER) ?: "chrono"
        val newPrivateKeyPath = intent?.getStringExtra(EXTRA_KEY_PATH) ?: ""

        if (newHost == hostConfig &&
            newPort == portConfig &&
            newUser == userConfig &&
            newPrivateKeyPath == privateKeyPath &&
            proxyProcess?.isAlive == true
        ) {
            Log.d(TAG, "Profile unchanged and alive. Skipping restart.")
            return START_STICKY
        }

        hostConfig = newHost
        portConfig = newPort
        userConfig = newUser
        privateKeyPath = newPrivateKeyPath

        Log.d(TAG, "Service starting/updating profile. Target: $userConfig@$hostConfig:$portConfig")
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        mainExecutionJob?.cancel()
        mainExecutionJob = launch {
            try {
                startTunnelSequence()
            } catch (e: CancellationException) {
                Log.d(TAG, "Startup job cancelled (normal for profile switch)")
            }
        }

        return START_STICKY
    }

    private fun sendCurrentStatus() {
        val proxyAlive = proxyProcess?.isAlive == true
        sendStatusUpdate(
            "Disabled",
            if (proxyAlive) tunnelState else "Stopped"
        )
    }

    private suspend fun startTunnelSequence() {
        tunnelMutex.withLock {
            isRestarting = true
            try {
                tunnelState = "Starting..."
                sendStatusUpdate("Disabled", tunnelState)
                tunnelMonitorJob?.cancel()
                proxyReaderJob?.cancel()

                stopBackgroundProcesses()
                cleanUpLingeringProcesses()

                val proxyPath = copyBinaryToFilesDir(PROXY_CLIENT_BINARY_NAME)

                if (proxyPath != null) {
                    if (privateKeyPath.isNotEmpty()) {
                        try {
                            Runtime.getRuntime()
                            .exec(arrayOf("chmod", "600", privateKeyPath))
                            .waitFor()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to chmod key: ${e.message}")
                        }
                    }

                    val success = executeCommands(proxyPath)
                    if (success && isActive) {
                        tunnelMonitorJob = launch { startTunnelMonitor() }
                    } else if (isActive) {
                        Log.e(TAG, "Failed to start SSH tunnel. Stopping service.")
                        tunnelState = "Stopped"
                        stopSelf()
                    }
                } else {
                    sendErrorMessage("Failed to copy proxy-client binary from assets.")
                    tunnelState = "Stopped"
                    stopSelf()
                }
            } finally {
                isRestarting = false
            }
        }
    }

    private suspend fun startTunnelMonitor() {
        while (isActive) {
            delay(MONITOR_INTERVAL_MS)

            val proxyAlive = proxyProcess?.isAlive == true

            if (!proxyAlive) {
                if (isActive && !isRestarting) {
                    Log.w(TAG, "SSH Tunnel failure detected. Restarting...")
                    tunnelState = "Stopped"
                    launch { startTunnelSequence() }
                    break
                }
            } else {
                // Отправляем актуальное динамическое состояние (Running или Reconnecting...)
                sendStatusUpdate("Disabled", tunnelState)
            }
        }
    }

    private suspend fun executeCommands(proxyPath: String): Boolean {
        tunnelState = "Starting Go SSH Client..."
        sendStatusUpdate("Disabled", tunnelState)

        val targetSshAddr = if (hostConfig.contains(":")) hostConfig else "$hostConfig:$portConfig"

        val proxyCommand = listOf(
            proxyPath,
            "-upstream=127.0.0.1:2080",
            privateKeyPath,
            targetSshAddr,
            "127.0.0.1:1080"
        )

        val proxyResult = startProcessWithOutputCheck(
            proxyCommand,
            PROXY_CLIENT_BINARY_NAME,
            12000L,
            "[Успех]"
        )

        proxyProcess = proxyResult.second

        if (proxyResult.first.contains("[Успех]") && proxyProcess?.isAlive == true) {
            proxyReaderJob = launch {
                readProcessOutput(proxyProcess!!, PROXY_CLIENT_BINARY_NAME)
            }
            tunnelState = "Running"
            sendStatusUpdate("Disabled", tunnelState)
            return true
        } else {
            Log.e(TAG, "Proxy client failed to establish connection or died.")
            sendErrorMessage("SSH Tunnel failed: ${proxyResult.first}")
            killProcess(proxyProcess)
            proxyProcess = null
            tunnelState = "Stopped"
        }
        return false
    }

    private suspend fun startProcessWithOutputCheck(
        command: List<String>,
        logTag: String,
        timeout: Long,
        successMsg: String?
    ): Pair<String, Process> {
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            val result = withTimeoutOrNull(timeout) {
                while (isActive) {
                    if (reader.ready()) {
                        val line = reader.readLine() ?: break
                        output.append(line).append("\n")
                        Log.d(TAG, "$logTag: $line")
                        sendLogLine(line)

                        if (successMsg != null && line.contains(successMsg)) {
                            return@withTimeoutOrNull "SUCCESS"
                        }
                    } else {
                        delay(100)
                    }
                }
                "TIMEOUT"
            }

            val finalOutput = if (successMsg == null && process.isAlive) "Started" else output.toString().trim()
            Pair(
                if (successMsg != null && result == "SUCCESS") successMsg else finalOutput,
                    process
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
                Log.e(TAG, "Error starting $logTag: ${e.message}")
                Pair("Error: ${e.message}", ProcessBuilder("echo").start())
        }
    }

    private suspend fun readProcessOutput(process: Process, logTag: String) {
        withContext(Dispatchers.IO) {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            try {
                while (isActive && process.isAlive) {
                    val line = reader.readLine() ?: break
                    Log.d(TAG, "$logTag Live: $line")
                    sendLogLine(line)

                    // Парсинг логов для динамического переключения состояний в интерфейсе
                    if (line.contains("[Туннель] Подключение разорвано") || line.contains("Ошибка переподключения")) {
                        if (tunnelState != "Reconnecting...") {
                            tunnelState = "Reconnecting..."
                            sendStatusUpdate("Disabled", tunnelState)
                        }
                    } else if (line.contains("[Туннель] Переподключение успешно завершено!")) {
                        if (tunnelState != "Running") {
                            tunnelState = "Running"
                            sendStatusUpdate("Disabled", tunnelState)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Output Reader Error ($logTag): ${e.message}")
            } finally {
                try {
                    reader.close()
                } catch (e: Exception) {}
            }
        }
    }

    private fun sendLogLine(line: String) {
        val intent = Intent(ACTION_LOG_OUTPUT).apply {
            putExtra(EXTRA_LOG_LINE, line)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendErrorMessage(msg: String) {
        val intent = Intent(ACTION_ERROR).apply { putExtra(EXTRA_ERROR_MESSAGE, msg) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Tunnel Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            service.createNotificationChannel(chan)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle("Go SSH Pipeline")
        .setContentText("SSH Tunnel over SOCKS5 & TLS active")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentIntent(pendingIntent)
        .build()
    }

    private fun sendStatusUpdate(slipstreamStatus: String, sshStatus: String) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS_SLIPSTREAM, slipstreamStatus)
            putExtra(EXTRA_STATUS_SSH, sshStatus)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun copyBinaryToFilesDir(name: String): String? {
        val file = File(filesDir, name)
        return try {
            if (!file.exists()) {
                assets.open(name).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            file.setExecutable(true, false)
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun cleanUpLingeringProcesses() {
        try {
            Runtime.getRuntime().exec(arrayOf("killall", "-9", PROXY_CLIENT_BINARY_NAME)).waitFor()
        } catch (e: Exception) {}
    }

    private fun killProcess(p: Process?) {
        try {
            if (p?.isAlive == true) {
                p.destroy()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) p.destroyForcibly()
            }
        } catch (e: Exception) {}
    }

    private fun stopBackgroundProcesses() {
        killProcess(proxyProcess)
        proxyProcess = null
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed.")
        tunnelState = "Stopped"
        mainExecutionJob?.cancel()
        job.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        stopBackgroundProcesses()
        super.onDestroy()
    }
}
