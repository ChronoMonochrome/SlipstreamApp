package com.example.commandexecutor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import java.io.File
import java.io.InputStreamReader
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

// Data class to hold the execution result (for clarity in complex logic)
data class CommandResult(val exitCode: Int, val output: String)

class CommandService : LifecycleService() {

    private val TAG = "CommandService"
    private val NOTIFICATION_CHANNEL_ID = "CommandServiceChannel"
    private val NOTIFICATION_ID = 101

    // Global variables to hold references to the long-running processes
    private var slipstreamProcess: Process? = null
        private var sshProcess: Process? = null

            companion object {
                // Inputs for slipstream-client command line arguments
                const val EXTRA_IP_ADDRESS = "ip_address"
                const val EXTRA_DOMAIN = "domain_name"

                // Corrected binary name
                const val BINARY_NAME = "slipstream-client"
            }

            override fun onCreate() {
                super.onCreate()
                createNotificationChannel()
            }

            override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
                super.onStartCommand(intent, flags, startId)

                // Retrieve the user inputs
                val ipAddress = intent?.getStringExtra(EXTRA_IP_ADDRESS) ?: ""
                val domainName = intent?.getStringExtra(EXTRA_DOMAIN) ?: ""

                Log.d(TAG, "Service started. IP: $ipAddress, Domain: $domainName")

                // 1. Start as Foreground Service, required for long-running tasks on modern Android
                startForeground(NOTIFICATION_ID, buildForegroundNotification())

                // 2. Execute commands on a background thread (Dispatchers.IO)
                CoroutineScope(Dispatchers.IO).launch {

                    // --- UPDATED: Only clean up the unique slipstream-client process ---
                    cleanUpLingeringProcesses()

                    val binaryPath = copyBinaryToFilesDir(BINARY_NAME)
                    if (binaryPath != null) {
                        // Pass only required arguments
                        executeCommands(binaryPath, ipAddress, domainName)
                    } else {
                        Log.e(TAG, "Failed to prepare '$BINARY_NAME' binary for execution.")
                    }
                }

                return START_STICKY // Service should restart if killed by the OS
            }

            // --- Foreground Service Setup ---

            private fun createNotificationChannel() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "Command Executor Status",
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.createNotificationChannel(channel)
                }
            }

            private fun buildForegroundNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Slipstream Tunnel Running")
            .setContentText("Establishing DNS/QUIC covert channel...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .build()

            // --- Binary Preparation ---

            /**
             * Copies the bundled binary (assumed to be in `src/main/assets/slipstream-client`)
             * to the app's files directory and sets executable permissions.
             */
            private fun copyBinaryToFilesDir(binaryName: String): String? {
                val destFile = File(filesDir, binaryName)
                if (destFile.exists()) {
                    Log.d(TAG, "$binaryName already exists, skipping copy.")
                    return destFile.absolutePath
                }

                try {
                    assets.open(binaryName).use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    // Set executable permission (crucial for native binaries)
                    destFile.setExecutable(true, false)
                    Log.d(TAG, "Successfully copied and set executable permission for: ${destFile.absolutePath}")
                    return destFile.absolutePath
                } catch (e: Exception) {
                    Log.e(TAG, "Error copying binary '$binaryName': ${e.message}", e)
                    return null
                }
            }

            // --- Process Cleanup ---

            /**
             * Attempts to find and kill any running processes related to slipstream-client.
             * We avoid killing generic 'ssh' processes.
             */
            private fun cleanUpLingeringProcesses() {
                Log.w(TAG, "Attempting to clean up lingering processes...")

                // 1. Kill slipstream-client using pkill -9
                val pkillSlipstream = listOf("su", "-c", "pkill -9 ${BINARY_NAME}")
                executeCleanupCommand(pkillSlipstream, "pkill ${BINARY_NAME}")

                Log.i(TAG, "Cleanup completed. Only slipstream-client was targeted.")
            }

            /**
             * Executes a simple shell command intended for cleanup (no input, short execution).
             */
            private fun executeCleanupCommand(command: List<String>, logTag: String) {
                try {
                    Log.d(TAG, "Executing cleanup command: ${command.joinToString(" ")}")
                    val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()

                    // Wait a short time for the command to execute
                    val exited = process.waitFor(1, TimeUnit.SECONDS)

                    // Log output in case of error or warning (e.g., pkill not finding the process)
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val output = reader.readText().trim()
                    if (output.isNotBlank()) {
                        Log.w(TAG, "$logTag Output: $output")
                    }

                    if (exited) {
                        val exitCode = process.exitValue()
                        if (exitCode != 0) {
                            // pkill returns 1 if no process was found, which is a success for cleanup.
                            Log.w(TAG, "$logTag finished with exit code: $exitCode (Process likely not found, which is OK).")
                        } else {
                            Log.i(TAG, "$logTag successfully killed processes.")
                        }
                    } else {
                        // If it didn't exit, something went wrong with the cleanup command itself.
                        Log.e(TAG, "Cleanup command $logTag did not exit within timeout.")
                        process.destroyForcibly()
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error executing cleanup command $logTag: ${e.message}", e)
                }
            }

            // --- Command Execution ---

            /**
             * Executes the two required shell commands.
             * @param slipstreamClientPath The full path to the bundled slipstream-client binary.
             * @param ipAddress The user-provided IP address.
             * @param domainName The user-provided domain name.
             */
            private suspend fun executeCommands(
                slipstreamClientPath: String,
                ipAddress: String,
                domainName: String
            ) {

                // Command 1: Execution of the bundled slipstream-client binary
                val command1 = listOf(
                    slipstreamClientPath,
                    "--congestion-control=bbr",
                    "--resolver=$ipAddress:53",
                    "--domain=$domainName"
                )

                // 1. Start slipstream-client and wait for connection confirmation (2000ms timeout)
                val confirmationMessage = "Connection confirmed."
                val slipstreamResult = startLongRunningProcess(command1, BINARY_NAME, 2000L, confirmationMessage)
                slipstreamProcess = slipstreamResult.second

                if (slipstreamResult.first.contains(confirmationMessage, ignoreCase = false)) {
                    Log.i(TAG, "$BINARY_NAME output confirmed successful connection. Proceeding with ssh.")

                    delay(500L) // Wait for 500 milliseconds for port stability

                    // Command 2: Execution of system-preinstalled ssh binary, wrapped in 'su -c'.
                    // Removed -f flag to keep ssh running in the foreground as a child of 'su', allowing us to kill it easily.
                    val sshArgs = "-p 5201 -ND 3080 root@localhost"
                    val shellCommand = "ssh $sshArgs"

                    val command2 = listOf(
                        "su",
                        "-c",
                        shellCommand
                    )

                    // 2. Start ssh (no read timeout, we just start and let it run)
                    val sshResult = startLongRunningProcess(command2, "ssh", null, null)
                    sshProcess = sshResult.second

                } else {
                    Log.w(TAG, "$BINARY_NAME output did NOT contain \"$confirmationMessage\" within 2000ms. Stopping service.")
                    // Since the slipstream-client failed to connect or time out, we should stop it immediately.
                    killProcess(slipstreamProcess, BINARY_NAME)
                    // Stop the service itself since the tunnel setup failed.
                    stopSelf()
                }
            }

            /**
             * Starts a shell command, reads initial output with an optional timeout, and returns the Process object.
             * @param readTimeoutMillis If provided, the function returns as soon as the timeout expires or the success message is found.
             * @param successMessage Optional string to look for in the output to signal early success.
             * @return A tuple of (output, Process). Output contains all text read up to the exit/timeout.
             */
            private suspend fun startLongRunningProcess(
                command: List<String>,
                logTag: String,
                readTimeoutMillis: Long?,
                successMessage: String?
            ): Pair<String, Process> {
                try {
                    Log.i(TAG, "Starting $logTag execution: ${command.joinToString(" ")}")

                    val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()

                    val output = StringBuilder()
                    val reader = BufferedReader(InputStreamReader(process.inputStream))

                    if (readTimeoutMillis != null) {
                        withTimeoutOrNull(readTimeoutMillis) {
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                output.append(line).append('\n')
                                if (line?.isNotBlank() == true) Log.d(TAG, "$logTag Output: $line")

                                    // Break if success message is found
                                    if (successMessage != null && line?.contains(successMessage, ignoreCase = false) == true) {
                                        Log.d(TAG, "$logTag Success message found, stopping initial output read and proceeding.")
                                        break
                                    }
                            }
                            true
                        }
                    } else {
                        // For long-running processes like ssh, we only check if it started correctly
                        // and return the Process object quickly to allow background execution.
                        if (process.isAlive) {
                            Log.d(TAG, "$logTag is running in the background. Stopping output read.")
                        } else {
                            // Capture final output if the process exited immediately
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                output.append(line).append('\n')
                                if (line?.isNotBlank() == true) Log.d(TAG, "$logTag Initial Output: $line")
                            }
                        }
                    }

                    return Pair(output.toString().trim(), process)

                } catch (e: Exception) {
                    Log.e(TAG, "Error executing $logTag command: ${e.message}", e)
                    // Return dummy process if failed to start
                    return Pair("Execution Error: ${e.message}", ProcessBuilder("echo", "error").start())
                }
            }


            /**
             * Attempts a graceful (SIGTERM) then forced (SIGKILL) termination of a native process.
             */
            private fun killProcess(process: Process?, tag: String) {
                if (process != null && process.isAlive) {
                    Log.w(TAG, "Attempting graceful termination (SIGTERM) for $tag.")
                    process.destroy() // Sends SIGTERM (standard termination signal)
                    try {
                        // Wait up to 1 second for graceful shutdown
                        if (!process.waitFor(1000, TimeUnit.MILLISECONDS)) {
                            Log.e(TAG, "Graceful termination failed. Forcing kill (SIGKILL) for $tag.")
                            process.destroyForcibly()
                        }
                        Log.i(TAG, "$tag process successfully terminated.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error waiting/forcing termination for $tag: ${e.message}")
                        // Fallback: forcefully kill it
                        if (process.isAlive) {
                            process.destroyForcibly()
                        }
                    }
                } else if (process != null) {
                    Log.i(TAG, "$tag process was already terminated via other means (e.g., cleanup or natural exit).")
                }
            }

            override fun onBind(intent: Intent): IBinder? {
                super.onBind(intent)
                return null // We don't support binding in this simple implementation
            }

            override fun onDestroy() {
                // Stop all background processes we started
                stopBackgroundProcesses()
                super.onDestroy()
                Log.d(TAG, "Command Service destroyed.")
            }

            /**
             * Stops the processes that were started by the service and held in state variables.
             */
            private fun stopBackgroundProcesses() {
                Log.i(TAG, "Stopping foreground processes...")
                // Kill slipstream-client
                killProcess(slipstreamProcess, BINARY_NAME)
                slipstreamProcess = null

                // Kill ssh/su wrapper (relying on the stored process reference to kill only the intended tunnel)
                killProcess(sshProcess, "ssh")
                sshProcess = null
            }
}
