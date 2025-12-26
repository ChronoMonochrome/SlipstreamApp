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
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.coroutines.CoroutineContext

data class CommandResult(val exitCode: Int, val output: String)

class CommandService : LifecycleService(), CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO

    private val TAG = "CommandService"
    private val NOTIFICATION_CHANNEL_ID = "CommandServiceChannel"
    private val NOTIFICATION_ID = 101

    private val mainHandler = Handler(Looper.getMainLooper())

    private var slipstreamProcess: Process? = null
        private var proxyProcess: Process? = null
            private var slipstreamReaderJob: Job? = null
                private var proxyReaderJob: Job? = null
                    private var tunnelMonitorJob: Job? = null
                        private var mainExecutionJob: Job? = null

                            private val tunnelMutex = Mutex()

                            private var resolversConfig: ArrayList<String> = arrayListOf()
                                private var domainNameConfig: String = ""
                                    private var privateKeyPath: String = ""
                                        private var isRestarting = false

                                        companion object {
                                            const val EXTRA_RESOLVERS = "extra_ip_addresses_list"
                                            const val EXTRA_DOMAIN = "domain_name"
                                            const val EXTRA_KEY_PATH = "private_key_path"
                                            const val SLIPSTREAM_BINARY_NAME = "slipstream-client"
                                            const val PROXY_CLIENT_BINARY_NAME = "proxy-client"
                                            const val ACTION_STATUS_UPDATE = "net.typeblob.socks.STATUS_UPDATE"
                                            const val ACTION_ERROR = "net.typeblob.socks.ERROR"
                                            const val ACTION_REQUEST_STATUS = "net.typeblob.socks.REQUEST_STATUS"
                                            const val EXTRA_STATUS_SLIPSTREAM = "status_slipstream"
                                            const val EXTRA_STATUS_SSH = "status_ssh"
                                            const val EXTRA_ERROR_MESSAGE = "error_message"
                                            const val EXTRA_ERROR_OUTPUT = "error_output"
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
                                            val newPrivateKeyPath = intent?.getStringExtra(EXTRA_KEY_PATH) ?: ""

                                            if (newResolvers == resolversConfig && newDomain == domainNameConfig && slipstreamProcess?.isAlive == true) {
                                                Log.d(TAG, "Profile unchanged and alive. Skipping.")
                                                return START_STICKY
                                            }

                                            resolversConfig = newResolvers
                                            domainNameConfig = newDomain
                                            privateKeyPath = newPrivateKeyPath

                                            Log.d(TAG, "Service starting/updating profile. Domain: $domainNameConfig")
                                            startForeground(NOTIFICATION_ID, buildForegroundNotification())

                                            mainExecutionJob?.cancel()
                                            mainExecutionJob = launch {
                                                try {
                                                    startTunnelSequence(resolversConfig, domainNameConfig)
                                                } catch (e: CancellationException) {
                                                    Log.d(TAG, "Startup job cancelled (normal for profile switch)")
                                                }
                                            }

                                            return START_STICKY
                                        }

                                        private fun sendCurrentStatus(logTag: String) {
                                            val sAlive = slipstreamProcess?.isAlive == true
                                            val proxyAlive = proxyProcess?.isAlive == true
                                            sendStatusUpdate(if (sAlive) "Running" else "Stopped", if (proxyAlive) "Running" else "Stopped")
                                        }

                                        private suspend fun startTunnelSequence(resolvers: ArrayList<String>, domainName: String) {
                                            tunnelMutex.withLock {
                                                isRestarting = true
                                                try {
                                                    sendStatusUpdate("Cleaning up...", "Waiting...")
                                                    tunnelMonitorJob?.cancel()
                                                    slipstreamReaderJob?.cancel()
                                                    proxyReaderJob?.cancel()

                                                    stopBackgroundProcesses()
                                                    cleanUpLingeringProcesses()

                                                    val slipstreamPath = copyBinaryToFilesDir(SLIPSTREAM_BINARY_NAME)
                                                    val proxyPath = copyBinaryToFilesDir(PROXY_CLIENT_BINARY_NAME)

                                                    if (slipstreamPath != null && proxyPath != null) {
                                                        // Fix Private Key Permissions (Critical for SSH/Proxy clients)
                                                        if (privateKeyPath.isNotEmpty()) {
                                                            try {
                                                                Runtime.getRuntime().exec(arrayOf("chmod", "600", privateKeyPath)).waitFor()
                                                            } catch (e: Exception) { Log.e(TAG, "Failed to chmod key: ${e.message}") }
                                                        }

                                                        val success = executeCommands(slipstreamPath, proxyPath, resolvers, domainName)
                                                        if (success && isActive) {
                                                            tunnelMonitorJob = launch { startTunnelMonitor() }
                                                        } else if (isActive) {
                                                            Log.e(TAG, "Failed to start tunnel. Stopping service.")
                                                            stopSelf()
                                                        }
                                                    }
                                                } finally {
                                                    isRestarting = false
                                                }
                                            }
                                        }

                                        private suspend fun startTunnelMonitor() {
                                            while (isActive) {
                                                delay(MONITOR_INTERVAL_MS)

                                                val slipstreamAlive = slipstreamProcess?.isAlive == true
                                                val proxyAlive = proxyProcess?.isAlive == true

                                                if (!slipstreamAlive || !proxyAlive) {
                                                    if (isActive && !isRestarting) {
                                                        Log.w(TAG, "Tunnel failure detected. Restarting...")
                                                        launch { startTunnelSequence(resolversConfig, domainNameConfig) }
                                                        break
                                                    }
                                                } else {
                                                    sendStatusUpdate("Running", "Running")
                                                }
                                            }
                                        }

                                        private suspend fun executeCommands(slipstreamPath: String, proxyPath: String, resolvers: ArrayList<String>, domainName: String): Boolean {
                                            // 1. Start Slipstream
                                            val slipCommand = mutableListOf(slipstreamPath, "--congestion-control=bbr", "--domain=$domainName")
                                            resolvers.forEach { slipCommand.add("--resolver=${if (it.contains(":")) it else "$it:53"}") }

                                            val slipResult = startProcessWithOutputCheck(slipCommand, SLIPSTREAM_BINARY_NAME, 5000L, "Connection confirmed.")
                                            slipstreamProcess = slipResult.second

                                            if (slipResult.first.contains("Connection confirmed.")) {
                                                slipstreamReaderJob = launch { readProcessOutput(slipstreamProcess!!, SLIPSTREAM_BINARY_NAME) }
                                                sendStatusUpdate("Running", "Starting Proxy...")
                                                delay(1000L)

                                                // 2. Start Proxy Client
                                                val proxyCommand = listOf(proxyPath, privateKeyPath, "127.0.0.1:5201", "127.0.0.1:3080")

                                                // IMPORTANT: Passing null successMsg means we just check if it stays alive for 1.5 seconds
                                                val proxyResult = startProcessWithOutputCheck(proxyCommand, PROXY_CLIENT_BINARY_NAME, 1500L, null)
                                                proxyProcess = proxyResult.second

                                                if (proxyProcess?.isAlive == true) {
                                                    proxyReaderJob = launch { readProcessOutput(proxyProcess!!, PROXY_CLIENT_BINARY_NAME) }
                                                    sendStatusUpdate("Running", "Running")
                                                    return true
                                                } else {
                                                    Log.e(TAG, "Proxy client died immediately after start.")
                                                    sendErrorMessage("Proxy Client failed to start. Check key permissions.")
                                                }
                                            } else {
                                                sendErrorMessage("Slipstream failed: ${slipResult.first}")
                                            }
                                            return false
                                        }

                                        private suspend fun startProcessWithOutputCheck(
                                            command: List<String>, logTag: String, timeout: Long, successMsg: String?
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
                                                            if (successMsg != null && line.contains(successMsg)) return@withTimeoutOrNull "SUCCESS"
                                                        } else {
                                                            delay(100)
                                                        }
                                                    }
                                                    "TIMEOUT"
                                                }

                                                // If we aren't looking for a specific message, we just check if it crashed
                                                val finalOutput = if (successMsg == null && process.isAlive) "Started" else output.toString().trim()
                                                Pair(if (successMsg != null && result == "SUCCESS") successMsg else finalOutput, process)

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
                                                    }
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Output Reader Error ($logTag): ${e.message}")
                                                } finally {
                                                    try { reader.close() } catch (e: Exception) {}
                                                }
                                            }
                                        }

                                        private fun sendErrorMessage(msg: String) {
                                            val intent = Intent(ACTION_ERROR).apply {
                                                putExtra(EXTRA_ERROR_MESSAGE, msg)
                                            }
                                            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                                        }

                                        private fun createNotificationChannel() {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                val chan = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Tunnel Service", NotificationManager.IMPORTANCE_LOW)
                                                val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                                service.createNotificationChannel(chan)
                                            }
                                        }

                                        private fun buildForegroundNotification(): Notification {
                                            val intent = Intent(this, MainActivity::class.java)
                                            val pendingIntent = PendingIntent.getActivity(
                                                this, 0, intent,
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                                            )

                                            return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                                            .setContentTitle("Tunnel Service")
                                            .setContentText("Status: Active")
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
                                            } catch (e: Exception) { null }
                                        }

                                        private fun cleanUpLingeringProcesses() {
                                            try {
                                                Runtime.getRuntime().exec(arrayOf("killall", "-9", SLIPSTREAM_BINARY_NAME)).waitFor()
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
                                            killProcess(slipstreamProcess)
                                            proxyProcess = null
                                            slipstreamProcess = null
                                        }

                                        override fun onBind(intent: Intent): IBinder? {
                                            super.onBind(intent)
                                            return null
                                        }

                                        override fun onDestroy() {
                                            Log.d(TAG, "Service destroyed.")
                                            mainExecutionJob?.cancel()
                                            job.cancel()
                                            mainHandler.removeCallbacksAndMessages(null)
                                            stopBackgroundProcesses()
                                            super.onDestroy()
                                        }
}
