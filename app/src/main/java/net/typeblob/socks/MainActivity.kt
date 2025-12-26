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
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayList

import net.typeblob.socks.socks.util.Utility

class MainActivity : AppCompatActivity() {

    private lateinit var resolversContainer: LinearLayout
        private lateinit var addResolverButton: Button
            private lateinit var domainInput: EditText
                private lateinit var keyPathInput: EditText
                    private lateinit var btnBrowseKey: ImageButton
                        private lateinit var tunnelSwitch: Switch
                            private lateinit var profileSpinner: Spinner
                                private lateinit var addProfileButton: ImageButton
                                    private lateinit var deleteProfileButton: ImageButton

                                        private lateinit var slipstreamStatusIndicator: TextView
                                            private lateinit var slipstreamStatusText: TextView
                                                private lateinit var sshStatusIndicator: TextView
                                                    private lateinit var sshStatusText: TextView

                                                        private lateinit var sharedPreferences: SharedPreferences
                                                            private var isUpdatingSwitch = false
                                                            private var isSwitchingProfile = false

                                                            private var lastSelectedPosition: Int = 0

                                                                private val PREF_PROFILES_SET = "pref_profiles_set"
                                                                private val PREF_LAST_PROFILE = "pref_last_profile"
                                                                private val IP_SEPARATOR = "|"

                                                                private var profileList = mutableListOf<String>()
                                                                private lateinit var profileAdapter: ArrayAdapter<String>

                                                                    private val statusReceiver = object : BroadcastReceiver() {
                                                                        override fun onReceive(context: Context?, intent: Intent?) {
                                                                            when (intent?.action) {
                                                                                CommandService.ACTION_STATUS_UPDATE -> {
                                                                                    val slipstreamStatus = intent.getStringExtra(CommandService.EXTRA_STATUS_SLIPSTREAM)
                                                                                    val sshStatus = intent.getStringExtra(CommandService.EXTRA_STATUS_SSH)
                                                                                    updateStatusUI(slipstreamStatus, sshStatus)
                                                                                }
                                                                                CommandService.ACTION_ERROR -> {
                                                                                    val message = intent.getStringExtra(CommandService.EXTRA_ERROR_MESSAGE) ?: "Unknown Error"
                                                                                    Toast.makeText(this@MainActivity, "ERROR: $message", Toast.LENGTH_LONG).show()
                                                                                    updateStatusUI(slipstreamStatus = "Failed: $message", sshStatus = "Stopped")
                                                                                }
                                                                            }
                                                                        }
                                                                    }

                                                                    private val requestStoragePermissionLauncher = registerForActivityResult(
                                                                        ActivityResultContracts.RequestPermission()
                                                                    ) { isGranted ->
                                                                        if (isGranted) {
                                                                            openFilePicker()
                                                                        } else {
                                                                            Toast.makeText(this, "Storage permission required to browse files", Toast.LENGTH_SHORT).show()
                                                                        }
                                                                    }

                                                                    private val requestNotificationPermissionLauncher = registerForActivityResult(
                                                                        ActivityResultContracts.RequestPermission()
                                                                    ) { isGranted ->
                                                                        if (isGranted && tunnelSwitch.isChecked) startCommandService()
                                                                            else if (!isGranted) syncSwitchState(false)
                                                                    }

                                                                    // UPDATED: File Picker Launcher copies file to internal storage
                                                                    private val pickKeyFileLauncher = registerForActivityResult(
                                                                        ActivityResultContracts.OpenDocument()
                                                                    ) { uri: Uri? ->
                                                                        uri?.let {
                                                                            val localPath = copyUriToInternalStorage(it)
                                                                            if (localPath != null) {
                                                                                keyPathInput.setText(localPath)
                                                                                saveCurrentProfileData()
                                                                                Toast.makeText(this, "Key imported successfully", Toast.LENGTH_SHORT).show()
                                                                            } else {
                                                                                Toast.makeText(this, "Failed to import key", Toast.LENGTH_SHORT).show()
                                                                            }
                                                                        }
                                                                    }

                                                                    override fun onCreate(savedInstanceState: Bundle?) {
                                                                        super.onCreate(savedInstanceState)
                                                                        setContentView(R.layout.activity_main)

                                                                        sharedPreferences = getSharedPreferences("SlipstreamPrefs", Context.MODE_PRIVATE)

                                                                        resolversContainer = findViewById(R.id.resolvers_container)
                                                                        addResolverButton = findViewById(R.id.add_resolver_button)
                                                                        domainInput = findViewById(R.id.domain_input)
                                                                        keyPathInput = findViewById(R.id.key_path_input)
                                                                        btnBrowseKey = findViewById(R.id.btn_browse_key)
                                                                        tunnelSwitch = findViewById(R.id.tunnel_switch)
                                                                        profileSpinner = findViewById(R.id.profile_spinner)
                                                                        addProfileButton = findViewById(R.id.add_profile_button)
                                                                        deleteProfileButton = findViewById(R.id.delete_profile_button)

                                                                        slipstreamStatusIndicator = findViewById(R.id.slipstream_status_indicator)
                                                                        slipstreamStatusText = findViewById(R.id.slipstream_status_text)
                                                                        sshStatusIndicator = findViewById(R.id.ssh_status_indicator)
                                                                        sshStatusText = findViewById(R.id.ssh_status_text)

                                                                        setupProfiles()

                                                                        addResolverButton.setOnClickListener { addResolverInput("", true) }
                                                                        addProfileButton.setOnClickListener { showAddProfileDialog() }
                                                                        deleteProfileButton.setOnClickListener { deleteCurrentProfile() }

                                                                        btnBrowseKey.setOnClickListener {
                                                                            checkStoragePermissionAndOpenPicker()
                                                                        }

                                                                        tunnelSwitch.setOnCheckedChangeListener { _, isChecked ->
                                                                            if (isUpdatingSwitch) return@setOnCheckedChangeListener

                                                                                val currentProfile = profileSpinner.selectedItem?.toString()
                                                                                if (currentProfile != null) {
                                                                                    saveProfileData(currentProfile)
                                                                                }

                                                                                if (isChecked) checkPermissionsAndStartService()
                                                                                    else {
                                                                                        Utility.stopVpn(this);
                                                                                        stopService(Intent(this, CommandService::class.java))
                                                                                        updateStatusUI("Stopping...", "Stopping...")
                                                                                    }
                                                                        }

                                                                        val filter = IntentFilter().apply {
                                                                            addAction(CommandService.ACTION_STATUS_UPDATE)
                                                                            addAction(CommandService.ACTION_ERROR)
                                                                        }
                                                                        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter)
                                                                    }

                                                                    // Helper to copy the file from URI to App Internal Storage
                                                                    private fun copyUriToInternalStorage(uri: Uri): String? {
                                                                        return try {
                                                                            val fileName = getFileName(uri) ?: "id_ed25519"
                                                                            val destinationFile = File(filesDir, fileName)

                                                                            contentResolver.openInputStream(uri).use { input ->
                                                                                FileOutputStream(destinationFile).use { output ->
                                                                                    input?.copyTo(output)
                                                                                }
                                                                            }
                                                                            destinationFile.absolutePath
                                                                        } catch (e: Exception) {
                                                                            e.printStackTrace()
                                                                            null
                                                                        }
                                                                    }

                                                                    private fun getFileName(uri: Uri): String? {
                                                                        var name: String? = null
                                                                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                                                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                                                            if (cursor.moveToFirst()) {
                                                                                name = cursor.getString(nameIndex)
                                                                            }
                                                                        }
                                                                        return name
                                                                    }

                                                                    private fun checkStoragePermissionAndOpenPicker() {
                                                                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                                                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                                                                                openFilePicker()
                                                                            } else {
                                                                                requestStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                                                            }
                                                                        } else {
                                                                            openFilePicker()
                                                                        }
                                                                    }

                                                                    private fun openFilePicker() {
                                                                        pickKeyFileLauncher.launch(arrayOf("*/*"))
                                                                    }

                                                                    private fun checkPermissionsAndStartService() {
                                                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                                                                                startCommandService()
                                                                            } else {
                                                                                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                                            }
                                                                        } else {
                                                                            startCommandService()
                                                                        }
                                                                    }

                                                                    private fun setupProfiles() {
                                                                        val savedProfiles = sharedPreferences.getStringSet(PREF_PROFILES_SET, setOf("Default"))?.toMutableList() ?: mutableListOf("Default")
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

                                                                        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                                                                            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                                                                                if (isSwitchingProfile) return
                                                                                    val oldProfileName = profileList[lastSelectedPosition]
                                                                                    saveProfileData(oldProfileName)
                                                                                    loadProfileData(profileList[position])
                                                                                    lastSelectedPosition = position
                                                                                    sharedPreferences.edit().putString(PREF_LAST_PROFILE, profileList[position]).apply()
                                                                            }
                                                                            override fun onNothingSelected(parent: AdapterView<*>?) {}
                                                                        }
                                                                    }

                                                                    private fun loadProfileData(profileName: String) {
                                                                        isSwitchingProfile = true
                                                                        resolversContainer.removeAllViews()

                                                                        val ips = sharedPreferences.getString("profile_${profileName}_ips", "1.1.1.1")
                                                                        val domain = sharedPreferences.getString("profile_${profileName}_domain", "")
                                                                        // Defaulting to the internal files directory for safety
                                                                        val defaultKeyPath = File(filesDir, "id_ed25519").absolutePath
                                                                        val keyPath = sharedPreferences.getString("profile_${profileName}_key", defaultKeyPath)

                                                                        keyPathInput.setText(keyPath)
                                                                        domainInput.setText(domain)
                                                                        val ipList = ips?.split(IP_SEPARATOR)?.filter { it.isNotBlank() } ?: listOf("1.1.1.1")

                                                                        ipList.forEachIndexed { index, ip -> addResolverInput(ip, index > 0) }
                                                                        isSwitchingProfile = false
                                                                    }

                                                                    private fun saveProfileData(profileName: String) {
                                                                        val ipList = getIpListFromUI()
                                                                        sharedPreferences.edit().apply {
                                                                            putString("profile_${profileName}_ips", ipList.joinToString(IP_SEPARATOR))
                                                                            putString("profile_${profileName}_domain", domainInput.text.toString().trim())
                                                                            putString("profile_${profileName}_key", keyPathInput.text.toString().trim())
                                                                            apply()
                                                                        }
                                                                    }

                                                                    private fun saveCurrentProfileData() {
                                                                        val currentProfile = profileSpinner.selectedItem?.toString() ?: return
                                                                        saveProfileData(currentProfile)
                                                                    }

                                                                    private fun showAddProfileDialog() {
                                                                        val input = EditText(this).apply {
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
                                                                        }
                                                                        .setNegativeButton("Cancel", null)
                                                                        .show()
                                                                    }

                                                                    private fun saveProfilesList() {
                                                                        sharedPreferences.edit().putStringSet(PREF_PROFILES_SET, profileList.toSet()).apply()
                                                                    }

                                                                    private fun addResolverInput(ip: String, canDelete: Boolean) {
                                                                        val rowLayout = LinearLayout(this).apply {
                                                                            orientation = LinearLayout.HORIZONTAL
                                                                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                                                                setMargins(0, 0, 0, 16)
                                                                            }
                                                                        }

                                                                        val editText = EditText(this).apply {
                                                                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
                                                                            hint = "Resolver IP"
                                                                            setText(ip)
                                                                        }
                                                                        rowLayout.addView(editText)

                                                                        if (canDelete) {
                                                                            val deleteBtn = Button(this).apply {
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
                                                                        val keyPath = keyPathInput.text.toString().trim()

                                                                        if (ipList.isEmpty() || domainName.isBlank()) {
                                                                            Toast.makeText(this, "Configuration missing", Toast.LENGTH_SHORT).show()
                                                                            syncSwitchState(false)
                                                                            return
                                                                        }

                                                                        if (!File(keyPath).exists()) {
                                                                            Toast.makeText(this, "Key file not found.", Toast.LENGTH_LONG).show()
                                                                            syncSwitchState(false)
                                                                            return
                                                                        }

                                                                        // --- VPN PREPARATION LOGIC ---
                                                                        val vpnPrepareIntent = VpnService.prepare(this)
                                                                        if (vpnPrepareIntent != null) {
                                                                            // User hasn't allowed VPN yet or another app is using it.
                                                                            // This launches the system dialog "Allow this app to set up a VPN connection?"
                                                                            startActivityForResult(vpnPrepareIntent, 0)
                                                                        } else {
                                                                            // Permission already granted, we can start the services
                                                                            proceedWithStart(ipList, domainName, keyPath, ipList[0])
                                                                        }
                                                                    }

                                                                    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
                                                                        super.onActivityResult(requestCode, resultCode, data)
                                                                        if (resultCode == RESULT_OK) {
                                                                            // User accepted the VPN request. Get your UI data again and start.
                                                                            val ipList = getIpListFromUI()
                                                                            val domainName = domainInput.text.toString().trim()
                                                                            val keyPath = keyPathInput.text.toString().trim()

                                                                            proceedWithStart(ipList, domainName, keyPath, getIpListFromUI()[0])
                                                                        } else {
                                                                            // User clicked "Cancel"
                                                                            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
                                                                            syncSwitchState(false)
                                                                        }
                                                                    }

                                                                    private fun proceedWithStart(ipList: ArrayList<String>, domainName: String, keyPath: String, dns: String) {
                                                                        // 1. Start the CommandService
                                                                        val serviceIntent = Intent(this, CommandService::class.java).apply {
                                                                            putStringArrayListExtra(CommandService.EXTRA_RESOLVERS, ipList)
                                                                            putExtra(CommandService.EXTRA_DOMAIN, domainName)
                                                                            putExtra(CommandService.EXTRA_KEY_PATH, keyPath)
                                                                        }
                                                                        ContextCompat.startForegroundService(this, serviceIntent)

                                                                        // 2. Start the VPN Service
                                                                        // Ensure Utility.startVpn(this) actually calls startService()
                                                                        // for SocksVpnService with all the required EXTRAS (server, port, etc.)
                                                                        Utility.startVpn(this, dns)

                                                                        updateStatusUI("Starting...", "Waiting...")
                                                                    }

                                                                    private fun updateStatusUI(slipstreamStatus: String? = null, sshStatus: String? = null) {
                                                                        slipstreamStatus?.let { status ->
                                                                            slipstreamStatusText.text = status
                                                                            val color = when {
                                                                                status.contains("Running", true) -> Color.GREEN
                                                                                status.contains("Stopped", true) || status.contains("Failed", true) -> Color.RED
                                                                                else -> Color.YELLOW
                                                                            }
                                                                            slipstreamStatusIndicator.setTextColor(color)
                                                                            slipstreamStatusIndicator.text = if (color == Color.GREEN) "✔" else if (color == Color.RED) "❌" else "🟡"

                                                                            if (status.contains("Running", true)) syncSwitchState(true)
                                                                                else if (status.contains("Stopped", true) || status.contains("Failed", true)) syncSwitchState(false)
                                                                        }

                                                                        sshStatus?.let { status ->
                                                                            sshStatusText.text = status
                                                                            val color = if (status.contains("Running", true)) Color.GREEN else if (status.contains("Stopped", true)) Color.RED else Color.YELLOW
                                                                            sshStatusIndicator.setTextColor(color)
                                                                            sshStatusIndicator.text = if (color == Color.GREEN) "✔" else if (color == Color.RED) "❌" else "🟡"
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
                                                                        super.onDestroy()
                                                                    }
}
