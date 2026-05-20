package net.typeblob.socks

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.typeblob.socks.socks.util.Utility
import net.typeblob.socks.socks.util.Constants

class MainActivity : AppCompatActivity() {

private lateinit var sshHostInput: EditText
private lateinit var sshUserInput: EditText
private lateinit var sshPortInput: EditText
private lateinit var keyPathInput: EditText
private lateinit var btnBrowseKey: ImageButton
private lateinit var tunnelSwitch: Switch
private lateinit var proxyOnlySwitch: Switch
private lateinit var profileSpinner: Spinner
private lateinit var addProfileButton: ImageButton
private lateinit var deleteProfileButton: ImageButton
private lateinit var sshStatusIndicator: TextView
private lateinit var sshStatusText: TextView

private lateinit var logScrollView: ScrollView
private lateinit var logTextView: TextView

private lateinit var sharedPreferences: SharedPreferences
private var isUpdatingSwitch = false
private var isSwitchingProfile = false
private var lastSelectedPosition: Int = 0

private val PREF_PROFILES_SET = "pref_profiles_set"
private val PREF_LAST_PROFILE = "pref_last_profile"

private var profileList = mutableListOf<String>()
private lateinit var profileAdapter: ArrayAdapter<String>

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { selectedUri ->
            try {
                val currentProfile = profileSpinner.selectedItem?.toString() ?: "Default"
                val originalFileName = getFileName(selectedUri) ?: "id_ed25519_${currentProfile}"

                val localFile = File(filesDir, originalFileName)

                contentResolver.openInputStream(selectedUri).use { inputStream ->
                    FileOutputStream(localFile).use { outputStream ->
                        inputStream?.copyTo(outputStream)
                    }
                }

                val targetPath = localFile.absolutePath
                keyPathInput.setText(targetPath)
                logToConsole("Selected and imported key to internal storage: $targetPath")
            } catch (e: Exception) {
                logToConsole("Error importing key file: ${e.message}")
                Toast.makeText(this, "Failed to read selected file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            openFilePicker()
        } else {
            Toast.makeText(this, "Permission denied to read storage", Toast.LENGTH_SHORT).show()
            logToConsole("Error: Storage permission denied.")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            startCommandService()
        } else {
            Toast.makeText(this, "Notification permission is required", Toast.LENGTH_SHORT).show()
            logToConsole("Error: Notification permission denied.")
            syncSwitchState(false)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CommandService.ACTION_STATUS_UPDATE -> {
                    val sshStatus = intent.getStringExtra(CommandService.EXTRA_STATUS_SSH)
                    updateStatusUI(sshStatus)
                    logToConsole("Status: $sshStatus")
                }
                CommandService.ACTION_ERROR -> {
                    val message = intent.getStringExtra(CommandService.EXTRA_ERROR_MESSAGE) ?: "Unknown Error"
                    Toast.makeText(this@MainActivity, "ERROR: $message", Toast.LENGTH_LONG).show()
                    updateStatusUI("Failed: $message")
                    logToConsole("CRITICAL: $message")
                }
                CommandService.ACTION_LOG_OUTPUT -> {
                    val logLine = intent.getStringExtra(CommandService.EXTRA_LOG_LINE) ?: ""
                    if (logLine.isNotEmpty()) {
                        logToConsole(logLine)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("SlipstreamPrefs", Context.MODE_PRIVATE)

        sshHostInput = findViewById(R.id.ssh_host_input)
        sshUserInput = findViewById(R.id.ssh_user_input)
        sshPortInput = findViewById(R.id.ssh_port_input)
        keyPathInput = findViewById(R.id.key_path_input)
        btnBrowseKey = findViewById(R.id.btn_browse_key)
        tunnelSwitch = findViewById(R.id.tunnel_switch)
        proxyOnlySwitch = findViewById(R.id.proxy_only_switch)
        profileSpinner = findViewById(R.id.profile_spinner)
        addProfileButton = findViewById(R.id.add_profile_button)
        deleteProfileButton = findViewById(R.id.delete_profile_button)
        sshStatusIndicator = findViewById(R.id.ssh_status_indicator)
        sshStatusText = findViewById(R.id.ssh_status_text)

        logScrollView = findViewById(R.id.log_scroll_view)
        logTextView = findViewById(R.id.log_text_view)

        setupProfiles()

        addProfileButton.setOnClickListener { showAddProfileDialog() }
        deleteProfileButton.setOnClickListener { deleteCurrentProfile() }
        btnBrowseKey.setOnClickListener { checkStoragePermissionAndOpenPicker() }

        tunnelSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener

                val currentProfile = profileSpinner.selectedItem?.toString()
                if (currentProfile != null) {
                    saveProfileData(currentProfile)
                }

                if (isChecked) {
                    logToConsole("Starting pipeline sequence...")
                    checkPermissionsAndStartService()
                } else {
                    logToConsole("Tearing down active routing structures...")
                    Utility.stopVpn(this)
                    stopService(Intent(this, CommandService::class.java))
                    updateStatusUI("Stopped")
                }
        }

        val filter = IntentFilter().apply {
            addAction(CommandService.ACTION_STATUS_UPDATE)
            addAction(CommandService.ACTION_ERROR)
            addAction(CommandService.ACTION_LOG_OUTPUT)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter)
        logToConsole("System core loaded and ready.")
    }

    private fun logToConsole(message: String) {
        val timeStamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        logTextView.append("[$timeStamp] $message\n")
        logScrollView.post {
            logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun setupProfiles() {
        val savedProfiles = sharedPreferences.getStringSet(PREF_PROFILES_SET, setOf("Default")) ?: setOf("Default")
        profileList.clear()
        profileList.addAll(savedProfiles)

        profileAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, profileList)
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        profileSpinner.adapter = profileAdapter

        val lastProfile = sharedPreferences.getString(PREF_LAST_PROFILE, "Default")
        val position = profileList.indexOf(lastProfile)
        if (position >= 0) {
            profileSpinner.setSelection(position)
            lastSelectedPosition = position
            loadProfileData(profileList[position])
        }

        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (lastSelectedPosition != position) {
                    saveProfileData(profileList[lastSelectedPosition])
                    lastSelectedPosition = position
                    loadProfileData(profileList[position])
                    logToConsole("Switched configuration profile to: ${profileList[position]}")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun showAddProfileDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Add Profile")
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        builder.setView(input)

        builder.setPositiveButton("OK") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isNotEmpty() && !profileList.contains(name)) {
                profileList.add(name)
                profileAdapter.notifyDataSetChanged()
                sharedPreferences.edit().putStringSet(PREF_PROFILES_SET, profileList.toSet()).apply()
                profileSpinner.setSelection(profileList.indexOf(name))
                logToConsole("Profile '$name' generated.")
            }
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    private fun deleteCurrentProfile() {
        val currentProfile = profileSpinner.selectedItem?.toString() ?: return
        if (profileList.size <= 1) {
            Toast.makeText(this, "Cannot delete the last profile", Toast.LENGTH_SHORT).show()
            return
        }
        profileList.remove(currentProfile)
        profileAdapter.notifyDataSetChanged()
        sharedPreferences.edit().putStringSet(PREF_PROFILES_SET, profileList.toSet()).apply()
        profileSpinner.setSelection(0)
        logToConsole("Removed profile: $currentProfile")
    }

    private fun checkStoragePermissionAndOpenPicker() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            } else {
                openFilePicker()
            }
        } else {
            openFilePicker()
        }
    }

    private fun openFilePicker() {
        filePickerLauncher.launch("*/*")
    }

    private fun checkPermissionsAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startCommandService()
            }
        } else {
            startCommandService()
        }
    }

    private fun loadProfileData(profileName: String) {
        isSwitchingProfile = true

        val host = sharedPreferences.getString("profile_${profileName}_host", "")
        val user = sharedPreferences.getString("profile_${profileName}_user", "chrono")
        val port = sharedPreferences.getString("profile_${profileName}_port", "8000")
        val defaultKeyPath = File(filesDir, "id_ed25519").absolutePath
        val keyPath = sharedPreferences.getString("profile_${profileName}_key", defaultKeyPath)
        val proxyOnly = sharedPreferences.getBoolean("profile_${profileName}_proxy_only", true)

        sshHostInput.setText(host)
        sshUserInput.setText(user)
        sshPortInput.setText(port)
        keyPathInput.setText(keyPath)
        proxyOnlySwitch.isChecked = proxyOnly

        isSwitchingProfile = false
    }

    private fun saveProfileData(profileName: String) {
        sharedPreferences.edit().apply {
            putString("profile_${profileName}_host", sshHostInput.text.toString().trim())
            putString("profile_${profileName}_user", sshUserInput.text.toString().trim())
            putString("profile_${profileName}_port", sshPortInput.text.toString().trim())
            putString("profile_${profileName}_key", keyPathInput.text.toString().trim())
            putBoolean("profile_${profileName}_proxy_only", proxyOnlySwitch.isChecked)
            apply()
        }
    }

    private fun saveCurrentProfileData() {
        val currentProfile = profileSpinner.selectedItem?.toString() ?: return
        saveProfileData(currentProfile)
    }

    private fun startCommandService() {
        val host = sshHostInput.text.toString().trim()
        val user = sshUserInput.text.toString().trim()
        val port = sshPortInput.text.toString().trim().toIntOrNull() ?: 8000
        val keyPath = keyPathInput.text.toString().trim()

        if (host.isBlank() || user.isBlank()) {
            Toast.makeText(this, "SSH Host or Username missing", Toast.LENGTH_SHORT).show()
            syncSwitchState(false)
            return
        }

        if (!File(keyPath).exists()) {
            Toast.makeText(this, "Key file not found.", Toast.LENGTH_LONG).show()
            logToConsole("Error: Key path context target not found ('$keyPath')")
            syncSwitchState(false)
            return
        }

        logToConsole("Preparing standard VpnService initialization checks...")
        val vpnPrepareIntent = VpnService.prepare(this)
        if (vpnPrepareIntent != null) {
            startActivityForResult(vpnPrepareIntent, 0)
        } else {
            proceedWithStart(host, user, port, keyPath)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            val host = sshHostInput.text.toString().trim()
            val user = sshUserInput.text.toString().trim()
            val port = sshPortInput.text.toString().trim().toIntOrNull() ?: 8000
            val keyPath = keyPathInput.text.toString().trim()
            proceedWithStart(host, user, port, keyPath)
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
            logToConsole("Error: Target framework VpnService hook rejected.")
            syncSwitchState(false)
        }
    }

    private fun proceedWithStart(host: String, user: String, port: Int, keyPath: String) {
        logToConsole("Saving active session configurations...")
        sharedPreferences.edit().putBoolean("current_proxy_only", proxyOnlySwitch.isChecked).apply()

        // Инициализация новых полей ввода из activity_main.xml
        val upstreamPortInput = findViewById<android.widget.EditText>(R.id.upstream_port_input)
        val localProxyPortInput = findViewById<android.widget.EditText>(R.id.local_proxy_port_input)
        val dnsInput = findViewById<android.widget.EditText>(R.id.dns_input)

        // Чтение значений из интерфейса с фоллбеками на дефолты
        val upstreamPort = upstreamPortInput.text.toString().toIntOrNull() ?: 2080
        val localProxyPort = localProxyPortInput.text.toString().toIntOrNull() ?: 1080
        val dnsServer = dnsInput.text.toString().trim().let { if (it.isEmpty()) "77.88.8.8" else it }

        logToConsole("Starting CommandService background engine...")
        val serviceIntent = Intent(this, CommandService::class.java).apply {
            putExtra(CommandService.EXTRA_HOST, host)
            putExtra(CommandService.EXTRA_USER, user)
            putExtra(CommandService.EXTRA_PORT, port)
            putExtra(CommandService.EXTRA_KEY_PATH, keyPath)
            // Передаем динамические порты в CommandService
            putExtra(CommandService.EXTRA_UPSTREAM_PORT, upstreamPort)
            putExtra(CommandService.EXTRA_LOCAL_PROXY_PORT, localProxyPort)
        }
        androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)

        logToConsole("Configuring and launching target SocksVpnService...")
        val vpnIntent = Intent(this, SocksVpnService::class.java).apply {
            putExtra(Constants.INTENT_NAME, "SocksVPN")
            putExtra(Constants.INTENT_SERVER, "127.0.0.1")
            putExtra(Constants.INTENT_PORT, localProxyPort) // Используем настраиваемый порт локального прокси
            putExtra(Constants.INTENT_DNS, dnsServer)       // Используем настраиваемый DNS сервер
            putExtra(Constants.INTENT_ROUTE, "all")
        }
        startService(vpnIntent)

        updateStatusUI("Starting Tunnel...")
    }

    private fun updateStatusUI(sshStatus: String?) {
        sshStatus?.let { status ->
            sshStatusText.text = status
            val color = when {
                status.contains("Running", true) -> Color.GREEN
                status.contains("Stopped", true) || status.contains("Failed", true) -> Color.RED
                else -> Color.YELLOW
            }
            sshStatusIndicator.setTextColor(color)
            sshStatusIndicator.text = if (color == Color.GREEN) "✔" else if (color == Color.RED) "❌" else "🟡"

            if (status.contains("Running", true)) syncSwitchState(true)
                else if (status.contains("Stopped", true) || status.contains("Failed", true)) syncSwitchState(false)
        }
    }

    private fun syncSwitchState(checked: Boolean) {
        isUpdatingSwitch = true
        tunnelSwitch.isChecked = checked
        isUpdatingSwitch = false
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            // Если uri.path не null, берем всё, что после последнего '/', иначе вернется null
            result = uri.path?.substringAfterLast('/')
        }
        return result
    }

    override fun onDestroy() {
        saveCurrentProfileData()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
        super.onDestroy()
    }
}
