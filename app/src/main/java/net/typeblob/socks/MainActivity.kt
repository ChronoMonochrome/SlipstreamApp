package net.typeblob.socks

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var resolversContainer: LinearLayout
    private lateinit var addResolverButton: Button
    private lateinit var domainInput: EditText
    private lateinit var socks5PortInput: EditText
    private lateinit var tunnelSwitch: Switch
    private lateinit var profileSpinner: Spinner
    private lateinit var addProfileButton: ImageButton
    private lateinit var deleteProfileButton: ImageButton

    private lateinit var slipstreamStatusIndicator: TextView
    private lateinit var slipstreamStatusText: TextView
    private lateinit var socks5StatusIndicator: TextView
    private lateinit var socks5StatusText: TextView
    
    private lateinit var logScrollView: ScrollView
    private lateinit var logTextView: TextView
    private lateinit var clearLogButton: Button
    private lateinit var copyLogButton: Button
    private val logBuilder = StringBuilder()
    private val maxLogLines = 200

    private lateinit var sharedPreferences: SharedPreferences
    private var isUpdatingSwitch = false
    private var isSwitchingProfile = false

    private var lastSelectedPosition: Int = 0

    private val PREF_PROFILES_SET = "pref_profiles_set"
    private val PREF_LAST_PROFILE = "pref_last_profile"
    private val IP_SEPARATOR = "|"

    private var profileList = mutableListOf<String>()
    private lateinit var profileAdapter: ArrayAdapter<String>

    private val statusReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        CommandService.ACTION_STATUS_UPDATE -> {
                            val slipstreamStatus =
                                    intent.getStringExtra(CommandService.EXTRA_STATUS_SLIPSTREAM)
                            val socksStatus = intent.getStringExtra(CommandService.EXTRA_STATUS_SOCKS5)
                            updateStatusUI(slipstreamStatus, socksStatus)
                            addLog("Status: Slipstream=$slipstreamStatus, SOCKS5=$socksStatus")
                        }
                        CommandService.ACTION_ERROR -> {
                            val message =
                                    intent.getStringExtra(CommandService.EXTRA_ERROR_MESSAGE)
                                            ?: "Unknown Error"
                            Toast.makeText(this@MainActivity, "ERROR: $message", Toast.LENGTH_LONG)
                                    .show()
                            updateStatusUI(
                                    slipstreamStatus = "Failed: $message",
                                    socksStatus = "Stopped"
                            )
                            addLog("ERROR: $message", isError = true)
                        }
                        CommandService.ACTION_LOG -> {
                            val logMessage = intent.getStringExtra(CommandService.EXTRA_LOG_MESSAGE) ?: ""
                            val isError = intent.getBooleanExtra(CommandService.EXTRA_LOG_IS_ERROR, false)
                            addLog("[Service] $logMessage", isError = isError)
                        }
                    }
                }
            }

    private val requestNotificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted && tunnelSwitch.isChecked) {
                    startCommandService()
                    addLog("Notification permission granted")
                } else if (!isGranted) {
                    syncSwitchState(false)
                    addLog("Notification permission denied", isError = true)
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences("SlipstreamPrefs", Context.MODE_PRIVATE)

        resolversContainer = findViewById(R.id.resolvers_container)
        addResolverButton = findViewById(R.id.add_resolver_button)
        domainInput = findViewById(R.id.domain_input)
        socks5PortInput = findViewById(R.id.socks5_port_input)
        tunnelSwitch = findViewById(R.id.tunnel_switch)
        profileSpinner = findViewById(R.id.profile_spinner)
        addProfileButton = findViewById(R.id.add_profile_button)
        deleteProfileButton = findViewById(R.id.delete_profile_button)

        slipstreamStatusIndicator = findViewById(R.id.slipstream_status_indicator)
        slipstreamStatusText = findViewById(R.id.slipstream_status_text)
        socks5StatusIndicator = findViewById(R.id.socks5_status_indicator)
        socks5StatusText = findViewById(R.id.socks5_status_text)
        
        logScrollView = findViewById(R.id.log_scroll_view)
        logTextView = findViewById(R.id.log_text_view)
        clearLogButton = findViewById(R.id.clear_log_button)
        copyLogButton = findViewById(R.id.copy_log_button)
        
        // Enable scrolling and text selection
        logTextView.movementMethod = ScrollingMovementMethod()
        logTextView.setTextIsSelectable(true)
        
        clearLogButton.setOnClickListener { 
            clearLog()
        }
        
        copyLogButton.setOnClickListener {
            copyLogsToClipboard()
        }

        setupProfiles()
        
        addLog("SlipstreamApp started - SOCKS5 mode (No VPN)")

        addResolverButton.setOnClickListener { addResolverInput("", true) }
        addProfileButton.setOnClickListener { showAddProfileDialog() }
        deleteProfileButton.setOnClickListener { deleteCurrentProfile() }

        tunnelSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener

            val currentProfile = profileSpinner.selectedItem?.toString()
            if (currentProfile != null) {
                saveProfileData(currentProfile)
            }

            if (isChecked) {
                addLog("Starting SOCKS5 proxy service...")
                checkPermissionsAndStartService()
            } else {
                addLog("Stopping SOCKS5 proxy service...")
                stopService(Intent(this, CommandService::class.java))
                updateStatusUI("Stopped", "Stopped")
            }
        }

        val filter =
                IntentFilter().apply {
                    addAction(CommandService.ACTION_STATUS_UPDATE)
                    addAction(CommandService.ACTION_ERROR)
                    addAction(CommandService.ACTION_LOG)
                }
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter)
    }
    
    private fun addLog(message: String, isError: Boolean = false) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val prefix = if (isError) "❌" else "ℹ️"
        val logLine = "[$timestamp] $prefix $message\n"
        
        logBuilder.append(logLine)
        
        val lines = logBuilder.lines()
        if (lines.size > maxLogLines) {
            logBuilder.clear()
            logBuilder.append(lines.takeLast(maxLogLines).joinToString("\n"))
        }
        
        runOnUiThread {
            logTextView.text = logBuilder.toString()
            // Auto-scroll to bottom
            logScrollView.post {
                logScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
    
    private fun clearLog() {
        logBuilder.clear()
        logTextView.text = ""
        addLog("Log cleared")
    }
    
    private fun copyLogsToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("SlipstreamApp Logs", logBuilder.toString())
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        addLog("Logs copied to clipboard")
    }

    private fun checkPermissionsAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED
            ) {
                startCommandService()
            } else {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startCommandService()
        }
    }

    private fun setupProfiles() {
        val savedProfiles =
                sharedPreferences.getStringSet(PREF_PROFILES_SET, setOf("Default"))?.toMutableList()
                        ?: mutableListOf("Default")
        profileList.clear()
        profileList.addAll(savedProfiles.sorted())

        profileAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, profileList)
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        profileSpinner.adapter = profileAdapter

        val lastProfile = sharedPreferences.getString(PREF_LAST_PROFILE, "Default")
        val lastIndex = profileList.indexOf(lastProfile).let { if (it == -1) 0 else it }

        lastSelectedPosition = lastIndex
        profileSpinner.setSelection(lastIndex)
        loadProfileData(profileList[lastIndex])

        profileSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long
                    ) {
                        if (isSwitchingProfile) return
                        val oldProfileName = profileList[lastSelectedPosition]
                        saveProfileData(oldProfileName)
                        loadProfileData(profileList[position])
                        lastSelectedPosition = position
                        sharedPreferences
                                .edit()
                                .putString(PREF_LAST_PROFILE, profileList[position])
                                .apply()
                        addLog("Switched to profile: ${profileList[position]}")
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
    }

    private fun loadProfileData(profileName: String) {
        isSwitchingProfile = true
        resolversContainer.removeAllViews()

        val ips = sharedPreferences.getString("profile_${profileName}_ips", "1.1.1.1")
        val domain = sharedPreferences.getString("profile_${profileName}_domain", "")
        val socks5Port = sharedPreferences.getString("profile_${profileName}_socks5_port", "1080")

        socks5PortInput.setText(socks5Port)
        domainInput.setText(domain)
        val ipList = ips?.split(IP_SEPARATOR)?.filter { it.isNotBlank() } ?: listOf("1.1.1.1")

        ipList.forEachIndexed { index, ip -> addResolverInput(ip, index > 0) }
        isSwitchingProfile = false
        addLog("Loaded profile: $profileName")
    }

    private fun saveProfileData(profileName: String) {
        val ipList = getIpListFromUI()
        sharedPreferences.edit().apply {
            putString("profile_${profileName}_ips", ipList.joinToString(IP_SEPARATOR))
            putString("profile_${profileName}_domain", domainInput.text.toString().trim())
            putString("profile_${profileName}_socks5_port", socks5PortInput.text.toString().trim())
            apply()
        }
    }

    private fun saveCurrentProfileData() {
        val currentProfile = profileSpinner.selectedItem?.toString() ?: return
        saveProfileData(currentProfile)
    }

    private fun showAddProfileDialog() {
        val input =
                EditText(this).apply {
                    hint = "Profile Name"
                    inputType = InputType.TYPE_CLASS_TEXT
                }
        AlertDialog.Builder(this)
                .setTitle("New Profile")
                .setView(input)
                .setPositiveButton("Create") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty() && !profileList.contains(name)) {
                        saveCurrentProfileData()
                        sharedPreferences.edit().apply {
                            putString("profile_${name}_ips", "1.1.1.1")
                            putString("profile_${name}_domain", "")
                            putString("profile_${name}_socks5_port", "1080")
                            apply()
                        }

                        profileList.add(name)
                        profileList.sort()
                        profileAdapter.notifyDataSetChanged()
                        saveProfilesList()

                        val newIndex = profileList.indexOf(name)
                        profileSpinner.setSelection(newIndex)
                        lastSelectedPosition = newIndex
                        loadProfileData(name)
                        addLog("Created new profile: $name")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
    }

    private fun deleteCurrentProfile() {
        if (profileList.size <= 1) {
            Toast.makeText(this, "Cannot delete the last profile", Toast.LENGTH_SHORT).show()
            return
        }
        val currentProfile = profileSpinner.selectedItem.toString()
        AlertDialog.Builder(this)
                .setTitle("Delete Profile")
                .setMessage("Are you sure you want to delete '$currentProfile'?")
                .setPositiveButton("Delete") { _, _ ->
                    profileList.remove(currentProfile)
                    saveProfilesList()
                    profileAdapter.notifyDataSetChanged()
                    lastSelectedPosition = 0
                    profileSpinner.setSelection(0)
                    loadProfileData(profileList[0])
                    addLog("Deleted profile: $currentProfile")
                }
                .setNegativeButton("Cancel", null)
                .show()
    }

    private fun saveProfilesList() {
        sharedPreferences.edit().putStringSet(PREF_PROFILES_SET, profileList.toSet()).apply()
    }

    private fun addResolverInput(ip: String, canDelete: Boolean) {
        val rowLayout =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { setMargins(0, 0, 0, 16) }
                }

        val editText =
                EditText(this).apply {
                    layoutParams =
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
                    hint = "Resolver IP"
                    setText(ip)
                }
        rowLayout.addView(editText)

        if (canDelete) {
            val deleteBtn =
                    Button(this).apply {
                        text = "–"
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.RED)
                        setOnClickListener { resolversContainer.removeView(rowLayout) }
                    }
            rowLayout.addView(deleteBtn)
        }
        resolversContainer.addView(rowLayout)
    }

    private fun getIpListFromUI(): ArrayList<String> {
        val ipList = ArrayList<String>()
        for (i in 0 until resolversContainer.childCount) {
            val row = resolversContainer.getChildAt(i) as? ViewGroup ?: continue
            for (j in 0 until row.childCount) {
                val child = row.getChildAt(j)
                if (child is EditText) {
                    val text = child.text.toString().trim()
                    if (text.isNotBlank()) ipList.add(text)
                }
            }
        }
        return ipList
    }

    private fun startCommandService() {
        val ipList = getIpListFromUI()
        val domainName = domainInput.text.toString().trim()
        val socks5Port = socks5PortInput.text.toString().trim()

        if (ipList.isEmpty() || domainName.isBlank()) {
            Toast.makeText(this, "Configuration missing", Toast.LENGTH_SHORT).show()
            syncSwitchState(false)
            addLog("Config missing - Resolvers: ${ipList.size}, Domain: ${domainName.isNotBlank()}", isError = true)
            return
        }

        if (socks5Port.isBlank() || socks5Port.toIntOrNull() == null) {
            Toast.makeText(this, "Invalid SOCKS5 port", Toast.LENGTH_SHORT).show()
            syncSwitchState(false)
            addLog("Invalid SOCKS5 port: $socks5Port", isError = true)
            return
        }

        addLog("Starting - Domain: $domainName, Resolvers: ${ipList.joinToString(",")}, Port: $socks5Port")

        val serviceIntent =
                Intent(this, CommandService::class.java).apply {
                    putStringArrayListExtra(CommandService.EXTRA_RESOLVERS, ipList)
                    putExtra(CommandService.EXTRA_DOMAIN, domainName)
                    putExtra(CommandService.EXTRA_SOCKS5_PORT, socks5Port)
                }
        ContextCompat.startForegroundService(this, serviceIntent)
        updateStatusUI("Starting...", "Waiting...")
        addLog("SOCKS5 proxy service started on port $socks5Port")
    }

    private fun updateStatusUI(slipstreamStatus: String? = null, socksStatus: String? = null) {
        slipstreamStatus?.let { status ->
            slipstreamStatusText.text = status
            val color =
                    when {
                        status.contains("Running", true) -> Color.GREEN
                        status.contains("Stopped", true) || status.contains("Failed", true) ->
                                Color.RED
                        else -> Color.YELLOW
                    }
            slipstreamStatusIndicator.setTextColor(color)
            slipstreamStatusIndicator.text =
                    if (color == Color.GREEN) "✔" else if (color == Color.RED) "❌" else "🟡"

            if (status.contains("Running", true)) syncSwitchState(true)
            else if (status.contains("Stopped", true) || status.contains("Failed", true))
                    syncSwitchState(false)
        }

        socksStatus?.let { status ->
            socks5StatusText.text = status
            val color =
                    if (status.contains("Running", true)) Color.GREEN
                    else if (status.contains("Stopped", true)) Color.RED else Color.YELLOW
            socks5StatusIndicator.setTextColor(color)
            socks5StatusIndicator.text =
                    if (color == Color.GREEN) "✔" else if (color == Color.RED) "❌" else "🟡"
        }
    }

    private fun syncSwitchState(checked: Boolean) {
        if (tunnelSwitch.isChecked != checked) {
            isUpdatingSwitch = true
            tunnelSwitch.isChecked = checked
            isUpdatingSwitch = false
        }
    }

    override fun onDestroy() {
        saveCurrentProfileData()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
        addLog("SlipstreamApp stopped")
        super.onDestroy()
    }
}