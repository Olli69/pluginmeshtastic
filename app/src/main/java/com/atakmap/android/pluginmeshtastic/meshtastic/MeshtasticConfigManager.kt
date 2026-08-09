package com.atakmap.android.pluginmeshtastic.meshtastic

import android.util.Log
import com.geeksville.mesh.AdminProtos
import com.geeksville.mesh.ChannelProtos
import com.geeksville.mesh.ConfigProtos
import com.geeksville.mesh.ModuleConfigProtos
import com.geeksville.mesh.Portnums
import com.geeksville.mesh.MeshProtos as GeeksvilleMeshProtos
import com.google.protobuf.ByteString
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Manages Meshtastic device configuration for optimal ATAK operation
 * Enforces covert, radio-silent settings for tactical operations
 */
class MeshtasticConfigManager(
    private val meshtasticManager: MeshtasticManager
) {
    companion object {
        private const val TAG = "MeshtasticConfigManager"
        private const val CONFIG_REQUEST_NONCE = 69420 // Nonce for config request
        private var currentPacketId = Random.nextInt(Integer.MAX_VALUE)
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Queue for admin messages that need to wait for node ID
    private val pendingAdminMessages = mutableListOf<AdminProtos.AdminMessage>()

    // Enhanced ACK tracking for admin messages (matching MeshtasticManager robustness)
    data class PendingAdminMessage(
        val packetId: UInt,
        val messageType: String,
        val timestamp: Long,
        val adminMessage: AdminProtos.AdminMessage,
        val retryCount: Int = 0,
        val maxRetries: Int = 1 // Allow 1 retry like sendAtakPluginMessage
    )

    data class AdminAckResult(
        val success: Boolean,
        val errorReason: String? = null,
        val isRetry: Boolean = false
    )

    private val pendingAcks = ConcurrentHashMap<UInt, PendingAdminMessage>()
    private val ackTimeout = 30000L // Increased to 30 seconds for admin messages (devices may need reboot time)
    
    // Configuration state
    private val _configState = MutableStateFlow(ConfigurationState.UNCONFIGURED)
    val configState: StateFlow<ConfigurationState> = _configState
    
    // Session key for admin operations
    private var sessionKey: ByteString? = null
    
    // Current device configuration (populated by config responses)
    private var currentDeviceConfig: ConfigProtos.Config? = null
    private var currentModuleConfigs: MutableMap<String, ModuleConfigProtos.ModuleConfig> = mutableMapOf()
    private var currentDeviceMetadata: GeeksvilleMeshProtos.DeviceMetadata? = null
    private var deviceMetadataRequestTime: Long = 0

    enum class ConfigurationState {
        UNCONFIGURED,
        RETRIEVING,
        WAITING_FOR_CONFIG,
        CONFIGURING,
        CONFIGURED,
        ERROR
    }
    
    /**
     * Start configuration process after BLE connection is established
     */
    fun startConfiguration() {
        Log.i(TAG, "Starting TAK configuration process")
        _configState.value = ConfigurationState.RETRIEVING
        
        // First request current configuration to verify device is ready
        requestCurrentConfiguration()
    }
    
    /**
     * Request current configuration from device using wantConfigId
     */
    private fun requestCurrentConfiguration() {
        Log.i(TAG, "Requesting current device configuration with nonce=$CONFIG_REQUEST_NONCE")
        
        // Use the local MeshProtos wrapper instead of generated proto
        val toRadio = com.atakmap.android.pluginmeshtastic.meshtastic.MeshProtos.ToRadio(
            wantConfigId = CONFIG_REQUEST_NONCE
        )
        
        meshtasticManager.sendToRadio(toRadio)
        _configState.value = ConfigurationState.WAITING_FOR_CONFIG
    }
    
    /**
     * Handle configuration complete response from device
     */
    fun handleConfigComplete(configCompleteId: Int) {
        Log.i(TAG, "Received config complete with id=$configCompleteId, current state=${_configState.value}")
        
        if (configCompleteId == CONFIG_REQUEST_NONCE) {
            when (_configState.value) {
                ConfigurationState.WAITING_FOR_CONFIG -> {
                    Log.i(TAG, "Config request complete, now reading existing configuration")
                    readExistingConfiguration()
                }
                ConfigurationState.UNCONFIGURED -> {
                    // This can happen if we reconnected before config started
                    Log.i(TAG, "Received config complete while unconfigured - reading existing configuration")
                    readExistingConfiguration()
                }
                else -> {
                    Log.w(TAG, "Received config complete in unexpected state: ${_configState.value}")
                }
            }
        }
    }
    
    /**
     * Read existing configuration from device before applying TAK settings
     */
    private fun readExistingConfiguration() {
        Log.i(TAG, "Reading existing device configuration to minimize changes")
        _configState.value = ConfigurationState.RETRIEVING
        
        scope.launch {
            // Request all configuration types
            requestDeviceConfig()
            // Small delay between requests to avoid overwhelming the device
            delay(100)
            requestModuleConfigs()
        }
    }
    
    /**
     * Request device configuration from the radio
     */
    private fun requestDeviceConfig() {
        try {
            val adminMsg = AdminProtos.AdminMessage.newBuilder()
                .setGetConfigRequest(AdminProtos.AdminMessage.ConfigType.DEVICE_CONFIG)
                .build()
            
            sendAdminMessage(adminMsg)
            Log.i(TAG, "Requested device configuration")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request device config", e)
        }
    }
    
    /**
     * Request device metadata from the radio
     */
    private fun requestDeviceMetadata() {
        try {
            Log.i(TAG, "Creating device metadata request...")
            val adminMsg = AdminProtos.AdminMessage.newBuilder()
                .setGetDeviceMetadataRequest(true)
                .build()
            
            deviceMetadataRequestTime = System.currentTimeMillis()
            Log.i(TAG, "Sending device metadata request via admin message")
            sendAdminMessage(adminMsg)
            Log.i(TAG, "Device metadata request sent successfully")
            
            // Set a timeout to check if we get a response
            scope.launch {
                delay(5000) // Wait 5 seconds for response
                if (currentDeviceMetadata == null && deviceMetadataRequestTime > 0) {
                    val elapsed = System.currentTimeMillis() - deviceMetadataRequestTime
                    Log.w(TAG, "Device metadata request timed out after ${elapsed}ms - device may not support metadata requests or already sent it automatically")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request device metadata", e)
        }
    }
    
    /**
     * Request module configurations from the radio
     */
    private suspend fun requestModuleConfigs() {
        try {
            // Request key module configs we need to check
            val moduleTypes = listOf(
                AdminProtos.AdminMessage.ModuleConfigType.MQTT_CONFIG,
                AdminProtos.AdminMessage.ModuleConfigType.TELEMETRY_CONFIG
            )
            
            moduleTypes.forEach { configType ->
                val adminMsg = AdminProtos.AdminMessage.newBuilder()
                    .setGetModuleConfigRequest(configType)
                    .build()
                
                sendAdminMessage(adminMsg)
                delay(50) // Small delay between requests
            }
            
            Log.i(TAG, "Requested module configurations")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request module configs", e)
        }
    }
    
    /**
     * Send admin message to device
     */
    private fun sendAdminMessage(adminMsg: AdminProtos.AdminMessage, needsAck: Boolean = true): UInt? {
        try {
            // Get our own node ID - admin messages should go to ourselves
            val myNodeInfo = meshtasticManager.getMyNodeInfo()

            if (myNodeInfo == null) {
                // Node ID not available yet, queue the message
                Log.w(TAG, "Node ID not available yet, queueing admin message (queue size: ${pendingAdminMessages.size + 1})")
                Log.w(TAG, "Connection state: ${meshtasticManager.connectionState.value}")
                Log.w(TAG, "Queuing message type: ${getAdminMessageType(adminMsg)}")
                synchronized(pendingAdminMessages) {
                    pendingAdminMessages.add(adminMsg)
                }
                return null
            }

            val myNodeId = myNodeInfo.myNodeNum
            val packetId = getNextPacketId().toUInt()
            val messageType = getAdminMessageType(adminMsg)

            Log.i(TAG, "Sending admin message to node: $myNodeId (type: $messageType, packetId: $packetId)")

            // Create data packet for admin message using the local wrapper
            val dataPacket = MeshProtos.DataPacket(
                to = myNodeId, // Send to our own node
                from = myNodeId, // From ourselves
                id = packetId,
                bytes = adminMsg.toByteArray(),
                portNum = Portnums.PortNum.ADMIN_APP_VALUE,
                channel = 0,
                wantAck = needsAck,
                hopLimit = 3 // Use hop limit 3 for admin messages
            )

            // Track this message if we need an ACK
            if (needsAck) {
                val pendingMsg = PendingAdminMessage(
                    packetId = packetId,
                    messageType = messageType,
                    timestamp = System.currentTimeMillis(),
                    adminMessage = adminMsg
                )
                pendingAcks[packetId] = pendingMsg
                Log.d(TAG, "Tracking ACK for packet $packetId ($messageType)")

                // Set timeout using proper coroutine scheduling (like MeshtasticManager)
                scope.launch {
                    delay(ackTimeout)
                    checkAckTimeout(packetId)
                }
            }

            // Create ToRadio message using the local wrapper
            val toRadio = MeshProtos.ToRadio(
                packet = dataPacket,
                wantConfigId = null
            )

            Log.d(TAG, "Sending admin packet: id=$packetId, size=${adminMsg.toByteArray().size} bytes, wantAck=$needsAck")

            // Send via MeshtasticManager
            meshtasticManager.sendToRadio(toRadio)

            return if (needsAck) packetId else null

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send admin message", e)
            return null
        }
    }

    /**
     * Handle ACK received for admin message
     */
    fun handleAdminAck(packetId: UInt) {
        pendingAcks[packetId]?.let { pendingMsg ->
            Log.i(TAG, "Received ACK for admin packet $packetId (${pendingMsg.messageType})")
            pendingAcks.remove(packetId)
        }
    }

    /**
     * Check for ACK timeout and handle retry logic (enhanced version matching MeshtasticManager)
     */
    private fun checkAckTimeout(packetId: UInt) {
        val pendingMsg = pendingAcks[packetId]
        if (pendingMsg != null) {
            val elapsed = System.currentTimeMillis() - pendingMsg.timestamp
            Log.w(TAG, "ACK timeout for packet $packetId (${pendingMsg.messageType}) after ${elapsed}ms")

            // Remove from pending (waitForAck will handle retry logic)
            pendingAcks.remove(packetId)

            // Log timeout for debugging
            Log.d(TAG, "Packet $packetId marked as timed out, retry will be handled by waitForAck if in progress")
        }
    }

    /**
     * Get status of pending ACKs
     */
    fun getPendingAckStatus(): String {
        return if (pendingAcks.isEmpty()) {
            "No pending ACKs"
        } else {
            "Pending ACKs: ${pendingAcks.values.joinToString(", ") { "${it.packetId}(${it.messageType})" }}"
        }
    }

    /**
     * Wait for specific ACK with timeout and retry capability
     */
    private suspend fun waitForAck(packetId: UInt, timeoutMs: Long = ackTimeout): AdminAckResult {
        val pendingMessage = pendingAcks[packetId]
        if (pendingMessage == null) {
            Log.w(TAG, "No pending message found for packet $packetId")
            return AdminAckResult(success = false, errorReason = "No pending message found")
        }

        // Wait for ACK using proper timeout scheduling (like MeshtasticManager)
        val result = withTimeoutOrNull(timeoutMs) {
            while (pendingAcks.containsKey(packetId)) {
                delay(100)
            }
            true
        }

        return if (result == true) {
            Log.i(TAG, "ACK received for packet $packetId (${pendingMessage.messageType})")
            AdminAckResult(success = true)
        } else {
            Log.w(TAG, "Timeout waiting for ACK for packet $packetId (${pendingMessage.messageType}) after ${timeoutMs}ms")

            // Check if we should retry
            if (pendingMessage.retryCount < pendingMessage.maxRetries) {
                Log.i(TAG, "Retrying admin message for packet $packetId (attempt ${pendingMessage.retryCount + 1}/${pendingMessage.maxRetries + 1})")

                // Remove current tracking
                pendingAcks.remove(packetId)

                // Retry with new packet ID
                val newPacketId = sendAdminMessage(pendingMessage.adminMessage, needsAck = true)
                if (newPacketId != null) {
                    // Update retry count
                    val retryMessage = pendingMessage.copy(
                        packetId = newPacketId,
                        retryCount = pendingMessage.retryCount + 1,
                        timestamp = System.currentTimeMillis()
                    )
                    pendingAcks[newPacketId] = retryMessage

                    // Wait for retry result
                    return waitForAck(newPacketId, timeoutMs).copy(isRetry = true)
                } else {
                    AdminAckResult(success = false, errorReason = "Failed to send retry")
                }
            } else {
                pendingAcks.remove(packetId)
                AdminAckResult(success = false, errorReason = "Timeout after ${pendingMessage.maxRetries + 1} attempts")
            }
        }
    }

    /**
     * Process queued admin messages now that node ID is available
     */
    fun processPendingAdminMessages() {
        synchronized(pendingAdminMessages) {
            if (pendingAdminMessages.isNotEmpty()) {
                Log.i(TAG, "Processing ${pendingAdminMessages.size} queued admin messages")
                val myNodeInfo = meshtasticManager.getMyNodeInfo()
                Log.i(TAG, "Node ID available: ${myNodeInfo?.myNodeNum} for pending messages")
                
                val messagesToSend = pendingAdminMessages.toList()
                pendingAdminMessages.clear()
                
                // Send all queued messages
                messagesToSend.forEach { adminMsg ->
                    Log.i(TAG, "Processing queued admin message: ${getAdminMessageType(adminMsg)}")
                    sendAdminMessage(adminMsg)
                }
            } else {
                Log.d(TAG, "No pending admin messages to process")
            }
        }
    }

    /**
     * Handle admin message response from device
     */
    fun handleAdminResponse(adminMessage: AdminProtos.AdminMessage) {
        Log.i(TAG, "Received admin response - processing message type: ${getAdminMessageType(adminMessage)}")
        
        // Handle admin messages using Meshtastic-Android pattern
        when (adminMessage.payloadVariantCase) {
            AdminProtos.AdminMessage.PayloadVariantCase.GET_CONFIG_RESPONSE -> {
                val config = adminMessage.getConfigResponse
                Log.i(TAG, "Admin: received config ${config.payloadVariantCase}")
                handleConfigResponse(config)
            }
            
            AdminProtos.AdminMessage.PayloadVariantCase.GET_MODULE_CONFIG_RESPONSE -> {
                val moduleConfig = adminMessage.getModuleConfigResponse
                Log.i(TAG, "Admin: received module config")
                handleModuleConfigResponse(moduleConfig)
            }
            
            AdminProtos.AdminMessage.PayloadVariantCase.GET_CHANNEL_RESPONSE -> {
                val channel = adminMessage.getChannelResponse
                Log.i(TAG, "Admin: received channel ${channel.index}")
                handleChannelResponse(channel)
            }
            
            AdminProtos.AdminMessage.PayloadVariantCase.GET_DEVICE_METADATA_RESPONSE -> {
                val metadata = adminMessage.getDeviceMetadataResponse
                val responseTime = if (deviceMetadataRequestTime > 0) {
                    System.currentTimeMillis() - deviceMetadataRequestTime
                } else 0
                Log.i(TAG, "Admin: received DeviceMetadata (Response time: ${responseTime}ms)")
                deviceMetadataRequestTime = 0 // Clear the request time
                handleDeviceMetadata(metadata)
            }
            
            AdminProtos.AdminMessage.PayloadVariantCase.GET_OWNER_RESPONSE -> {
                Log.i(TAG, "Admin: received Owner response")
                // Handle owner response if needed in the future
            }
            
            AdminProtos.AdminMessage.PayloadVariantCase.GET_CANNED_MESSAGE_MODULE_MESSAGES_RESPONSE -> {
                Log.i(TAG, "Admin: received Canned Message Module response")
                // Handle canned messages if needed
            }
            
            else -> {
                Log.w(TAG, "Admin: No special processing needed for ${adminMessage.payloadVariantCase}")
            }
        }
        
        // Always extract session passkey (following Meshtastic-Android pattern)
        if (adminMessage.sessionPasskey != null && !adminMessage.sessionPasskey.isEmpty) {
            sessionKey = adminMessage.sessionPasskey
            Log.d(TAG, "Admin: Updated session passkey")
        }
    }
    
    /**
     * Helper function to get admin message type for debugging
     */
    private fun getAdminMessageType(adminMsg: AdminProtos.AdminMessage): String {
        return try {
            when (adminMsg.payloadVariantCase) {
                AdminProtos.AdminMessage.PayloadVariantCase.GET_CONFIG_REQUEST -> "GET_CONFIG_REQUEST"
                AdminProtos.AdminMessage.PayloadVariantCase.GET_CONFIG_RESPONSE -> "GET_CONFIG_RESPONSE"
                AdminProtos.AdminMessage.PayloadVariantCase.GET_MODULE_CONFIG_REQUEST -> "GET_MODULE_CONFIG_REQUEST"
                AdminProtos.AdminMessage.PayloadVariantCase.GET_MODULE_CONFIG_RESPONSE -> "GET_MODULE_CONFIG_RESPONSE"
                AdminProtos.AdminMessage.PayloadVariantCase.GET_OWNER_REQUEST -> "GET_OWNER_REQUEST"
                AdminProtos.AdminMessage.PayloadVariantCase.GET_OWNER_RESPONSE -> "GET_OWNER_RESPONSE"
                AdminProtos.AdminMessage.PayloadVariantCase.GET_CHANNEL_REQUEST -> "GET_CHANNEL_REQUEST"
                AdminProtos.AdminMessage.PayloadVariantCase.GET_CHANNEL_RESPONSE -> "GET_CHANNEL_RESPONSE"
                AdminProtos.AdminMessage.PayloadVariantCase.SET_CONFIG -> "SET_CONFIG"
                AdminProtos.AdminMessage.PayloadVariantCase.SET_MODULE_CONFIG -> "SET_MODULE_CONFIG"
                AdminProtos.AdminMessage.PayloadVariantCase.SET_OWNER -> "SET_OWNER"
                AdminProtos.AdminMessage.PayloadVariantCase.SET_CHANNEL -> "SET_CHANNEL"
                AdminProtos.AdminMessage.PayloadVariantCase.GET_DEVICE_METADATA_REQUEST -> "GET_DEVICE_METADATA_REQUEST"
                AdminProtos.AdminMessage.PayloadVariantCase.GET_DEVICE_METADATA_RESPONSE -> "GET_DEVICE_METADATA_RESPONSE"
                AdminProtos.AdminMessage.PayloadVariantCase.REBOOT_OTA_SECONDS -> "REBOOT_OTA_SECONDS"
                AdminProtos.AdminMessage.PayloadVariantCase.EXIT_SIMULATOR -> "EXIT_SIMULATOR"
                AdminProtos.AdminMessage.PayloadVariantCase.REBOOT_SECONDS -> "REBOOT_SECONDS"
                AdminProtos.AdminMessage.PayloadVariantCase.SHUTDOWN_SECONDS -> "SHUTDOWN_SECONDS"
                AdminProtos.AdminMessage.PayloadVariantCase.NODEDB_RESET -> "NODEDB_RESET"
                AdminProtos.AdminMessage.PayloadVariantCase.BEGIN_EDIT_SETTINGS -> "BEGIN_EDIT_SETTINGS"
                AdminProtos.AdminMessage.PayloadVariantCase.COMMIT_EDIT_SETTINGS -> "COMMIT_EDIT_SETTINGS"
                AdminProtos.AdminMessage.PayloadVariantCase.GET_CANNED_MESSAGE_MODULE_MESSAGES_REQUEST -> "GET_CANNED_MESSAGE_MODULE_MESSAGES_REQUEST"
                AdminProtos.AdminMessage.PayloadVariantCase.GET_CANNED_MESSAGE_MODULE_MESSAGES_RESPONSE -> "GET_CANNED_MESSAGE_MODULE_MESSAGES_RESPONSE"
                AdminProtos.AdminMessage.PayloadVariantCase.GET_RINGTONE_REQUEST -> "GET_RINGTONE_REQUEST"
                AdminProtos.AdminMessage.PayloadVariantCase.GET_RINGTONE_RESPONSE -> "GET_RINGTONE_RESPONSE"
                AdminProtos.AdminMessage.PayloadVariantCase.SET_RINGTONE_MESSAGE -> "SET_RINGTONE_MESSAGE"
                AdminProtos.AdminMessage.PayloadVariantCase.REMOVE_BY_NODENUM -> "REMOVE_BY_NODENUM"
                AdminProtos.AdminMessage.PayloadVariantCase.SET_FAVORITE_NODE -> "SET_FAVORITE_NODE"
                AdminProtos.AdminMessage.PayloadVariantCase.REMOVE_FAVORITE_NODE -> "REMOVE_FAVORITE_NODE"
                AdminProtos.AdminMessage.PayloadVariantCase.SET_FIXED_POSITION -> "SET_FIXED_POSITION"
                AdminProtos.AdminMessage.PayloadVariantCase.REMOVE_FIXED_POSITION -> "REMOVE_FIXED_POSITION"
                AdminProtos.AdminMessage.PayloadVariantCase.SET_TIME_ONLY -> "SET_TIME_ONLY"
                AdminProtos.AdminMessage.PayloadVariantCase.ENTER_DFU_MODE_REQUEST -> "ENTER_DFU_MODE_REQUEST"
                AdminProtos.AdminMessage.PayloadVariantCase.DELETE_FILE_REQUEST -> "DELETE_FILE_REQUEST"
                AdminProtos.AdminMessage.PayloadVariantCase.PAYLOADVARIANT_NOT_SET -> "PAYLOADVARIANT_NOT_SET"
                null -> "NULL"
                else -> "UNKNOWN_TYPE"
            }
        } catch (e: Exception) {
            "ERROR_GETTING_TYPE: ${e.message}"
        }
    }

    /**
     * Handle device metadata received automatically during connection
     */
    fun handleDeviceMetadata(metadata: GeeksvilleMeshProtos.DeviceMetadata) {
        Log.i(TAG, "Received device metadata automatically from device during connection")
        currentDeviceMetadata = metadata
        deviceMetadataRequestTime = 0 // Clear any pending request timeout
        printDeviceMetadata(metadata)
    }
    
    /**
     * Handle config response received from device
     */
    fun handleConfigResponse(config: ConfigProtos.Config) {
        Log.i(TAG, "Received Config response - processing...")
        currentDeviceConfig = config
        
        Log.i(TAG, "Config response contains:")
        if (config.hasDevice()) Log.i(TAG, "  - Device config")
        if (config.hasLora()) Log.i(TAG, "  - LoRa config")  
        if (config.hasPosition()) Log.i(TAG, "  - Position config")
        if (config.hasPower()) Log.i(TAG, "  - Power config")
        if (config.hasBluetooth()) Log.i(TAG, "  - Bluetooth config")
        if (config.hasDisplay()) Log.i(TAG, "  - Display config")
        if (config.hasNetwork()) Log.i(TAG, "  - Network config")
    }
    
    /**
     * Handle module config response received from device
     */
    fun handleModuleConfigResponse(moduleConfig: ModuleConfigProtos.ModuleConfig) {
        Log.i(TAG, "Received ModuleConfig response - processing...")
        
        // Store module config by type based on which field is set
        val configKey = when {
            moduleConfig.hasMqtt() -> "mqtt"
            moduleConfig.hasTelemetry() -> "telemetry"
            moduleConfig.hasSerial() -> "serial"
            moduleConfig.hasExternalNotification() -> "external_notification"
            moduleConfig.hasStoreForward() -> "store_forward"
            moduleConfig.hasRangeTest() -> "range_test"
            moduleConfig.hasNeighborInfo() -> "neighbor_info"
            moduleConfig.hasAmbientLighting() -> "ambient_lighting"
            moduleConfig.hasDetectionSensor() -> "detection_sensor"
            moduleConfig.hasPaxcounter() -> "paxcounter"
            else -> "unknown"
        }
        
        Log.i(TAG, "ModuleConfig type: $configKey")
        currentModuleConfigs[configKey] = moduleConfig
    }
    
    /**
     * Handle channel response received from device
     */
    fun handleChannelResponse(channel: ChannelProtos.Channel) {
        Log.i(TAG, "Received Channel response - channel ${channel.index}")
        if (channel.hasSettings()) {
            val settings = channel.settings
            Log.i(TAG, "Channel name: ${settings.name}")
            Log.i(TAG, "Channel role: ${channel.role}")
            Log.i(TAG, "PSK length: ${settings.psk.size()}")
        }
    }

    /**
     * Print device metadata information
     */
    private fun printDeviceMetadata(metadata: GeeksvilleMeshProtos.DeviceMetadata) {
        Log.i(TAG, "=== Device Metadata ===")
        Log.i(TAG, "Firmware Version: ${metadata.firmwareVersion}")
        Log.i(TAG, "Device State Version: ${metadata.deviceStateVersion}")
        Log.i(TAG, "Can Shutdown: ${metadata.canShutdown}")
        Log.i(TAG, "Has WiFi: ${metadata.hasWifi}")
        Log.i(TAG, "Has Bluetooth: ${metadata.hasBluetooth}")
        Log.i(TAG, "Has Ethernet: ${metadata.hasEthernet}")
        Log.i(TAG, "Role: ${metadata.role}")
        Log.i(TAG, "Position Flags: ${metadata.positionFlags}")
        Log.i(TAG, "Hardware Model: ${metadata.hwModel}")
        Log.i(TAG, "Has Remote Hardware: ${metadata.hasRemoteHardware}")
        Log.i(TAG, "Has PKC: ${metadata.hasPKC}")
        Log.i(TAG, "Excluded Modules: ${metadata.excludedModules}")
        Log.i(TAG, "=====================")
    }


    // ---------------------------------------------------------------------------------------
    // User-driven configuration apply
    //
    // The device's existing config is read during the wantConfig handshake and cached by
    // MeshtasticManager. The Settings UI builds a candidate Config (each section derived from the
    // device's current section plus the user's edits) and calls applyUserConfig(). We diff each
    // section against the cached value and send SET_CONFIG only for sections that changed,
    // least-disruptive first so a device/power change that reboots the radio happens last.
    // ---------------------------------------------------------------------------------------
    fun applyUserConfig(
        newConfig: ConfigProtos.Config,
        applyChannel: Boolean,
        channelName: String,
        channelPassword: String,
        uplinkEnabled: Boolean,
        downlinkEnabled: Boolean,
        callback: (Boolean) -> Unit
    ) {
        scope.launch {
            var ok = true
            try {
                // LoRa (region / modem preset / hop limit / etc) — safe, rarely reboots.
                if (newConfig.hasLora() && newConfig.lora != meshtasticManager.getLoraConfig()) {
                    ok = sendConfigSection(ConfigProtos.Config.newBuilder().setLora(newConfig.lora).build(), "LoRa") && ok
                }
                // Channel (name / PSK / uplink / downlink).
                if (applyChannel) {
                    ok = sendChannelConfig(channelName, channelPassword, uplinkEnabled, downlinkEnabled) && ok
                }
                // Position.
                if (newConfig.hasPosition() && newConfig.position != meshtasticManager.getPositionConfig()) {
                    ok = sendConfigSection(ConfigProtos.Config.newBuilder().setPosition(newConfig.position).build(), "Position") && ok
                }
                // Display.
                if (newConfig.hasDisplay() && newConfig.display != meshtasticManager.getDisplayConfig()) {
                    ok = sendConfigSection(ConfigProtos.Config.newBuilder().setDisplay(newConfig.display).build(), "Display") && ok
                }
                // Network.
                if (newConfig.hasNetwork() && newConfig.network != meshtasticManager.getNetworkConfig()) {
                    ok = sendConfigSection(ConfigProtos.Config.newBuilder().setNetwork(newConfig.network).build(), "Network") && ok
                }
                // Bluetooth — changing this can drop the BLE link, so do it near the end.
                if (newConfig.hasBluetooth() && newConfig.bluetooth != meshtasticManager.getBluetoothConfig()) {
                    ok = sendConfigSection(ConfigProtos.Config.newBuilder().setBluetooth(newConfig.bluetooth).build(), "Bluetooth") && ok
                }
                // Power — may reboot.
                if (newConfig.hasPower() && newConfig.power != meshtasticManager.getPowerConfig()) {
                    ok = sendConfigSection(ConfigProtos.Config.newBuilder().setPower(newConfig.power).build(), "Power") && ok
                }
                // Device (role / rebroadcast) — most likely to reboot, so last.
                if (newConfig.hasDevice() && newConfig.device != meshtasticManager.getDeviceConfig()) {
                    ok = sendConfigSection(ConfigProtos.Config.newBuilder().setDevice(newConfig.device).build(), "Device") && ok
                }
            } catch (e: Exception) {
                Log.e(TAG, "applyUserConfig failed", e)
                ok = false
            }
            withContext(Dispatchers.Main) { callback(ok) }
        }
    }

    /** Send a single-section Config as a SET_CONFIG admin message and wait for its ACK. */
    private suspend fun sendConfigSection(config: ConfigProtos.Config, label: String): Boolean {
        Log.i(TAG, "Applying $label config")
        val adminMsg = AdminProtos.AdminMessage.newBuilder().apply {
            sessionKey?.let { sessionPasskey = it }
            setConfig = config
        }.build()
        val packetId = sendAdminMessage(adminMsg, needsAck = true)
        if (packetId == null) {
            Log.w(TAG, "$label config not sent (no node id / send failure)")
            return false
        }
        val result = waitForAck(packetId, 15000)
        if (!result.success) Log.w(TAG, "$label config failed: ${result.errorReason}")
        // Give the device a moment in case the change triggers a reboot before the next section.
        delay(1500)
        return result.success
    }

    /**
     * Configure the primary channel (index 0). Uses the device's current primary channel as a
     * base so unspecified fields (and the existing PSK when no new password is given) are kept.
     */
    private suspend fun sendChannelConfig(
        name: String,
        password: String,
        uplinkEnabled: Boolean,
        downlinkEnabled: Boolean
    ): Boolean {
        Log.i(TAG, "Applying channel config (name='$name', psk=${if (password.isEmpty()) "unchanged" else "updated"})")
        val current = meshtasticManager.getPrimaryChannel()
        val settingsBuilder = current?.settings?.toBuilder()
            ?: ChannelProtos.ChannelSettings.newBuilder()
        settingsBuilder.name = name
        settingsBuilder.uplinkEnabled = uplinkEnabled
        settingsBuilder.downlinkEnabled = downlinkEnabled
        if (password.isNotEmpty()) {
            settingsBuilder.psk = ByteString.copyFrom(generatePskFromPassword(password))
        }
        val channel = ChannelProtos.Channel.newBuilder()
            .setIndex(0)
            .setSettings(settingsBuilder.build())
            .setRole(ChannelProtos.Channel.Role.PRIMARY)
            .build()
        val adminMsg = AdminProtos.AdminMessage.newBuilder().apply {
            sessionKey?.let { sessionPasskey = it }
            setChannel = channel
        }.build()
        val packetId = sendAdminMessage(adminMsg, needsAck = true) ?: return false
        val result = waitForAck(packetId, 15000)
        if (!result.success) Log.w(TAG, "Channel config failed: ${result.errorReason}")
        delay(1000)
        return result.success
    }

    /**
     * Request session key for admin operations
     */
    fun requestSessionKey() {
        Log.i(TAG, "Requesting session key for admin operations")

        val adminMsg = AdminProtos.AdminMessage.newBuilder()
            .setGetConfigRequest(AdminProtos.AdminMessage.ConfigType.SESSIONKEY_CONFIG)
            .build()

        // Create data packet for admin message using the local wrapper
        val dataPacket = MeshProtos.DataPacket(
            to = 0u, // Send to self
            from = 0u,
            id = getNextPacketId().toUInt(),
            bytes = adminMsg.toByteArray(),
            portNum = Portnums.PortNum.ADMIN_APP_VALUE,
            channel = 0,
            wantAck = false,
            hopLimit = 3
        )

        // Create ToRadio message using the local wrapper
        val toRadio = MeshProtos.ToRadio(
            packet = dataPacket,
            wantConfigId = null
        )

        // Send via MeshtasticManager
        meshtasticManager.sendToRadio(toRadio)
    }

    /**
     * Get current device configuration
     */
    fun getCurrentConfiguration(callback: (String) -> Unit) {
        scope.launch {
            try {
                Log.i(TAG, "Fetching current device configuration")

                // Clear current config to ensure we get fresh data
                currentDeviceConfig = null
                currentModuleConfigs.clear()

                // Request device configuration
                requestDeviceConfig()

                // Wait a bit for device config
                delay(1000)

                // Request module configurations
                requestModuleConfigs()

                // Wait for responses
                delay(2000)

                // Get the current state
                val config = buildConfigurationString()

                withContext(Dispatchers.Main) {
                    callback(config)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get device configuration", e)
                withContext(Dispatchers.Main) {
                    callback("Failed to fetch configuration: ${e.message}")
                }
            }
        }
    }

    /**
     * Build configuration string for display
     */
    private fun buildConfigurationString(): String {
        val config = currentDeviceConfig
        val telemetryModule = currentModuleConfigs["telemetry"]
        val mqttModule = currentModuleConfigs["mqtt"]

        return if (config != null) {
            val telemetryInterval = if (telemetryModule?.hasTelemetry() == true) {
                telemetryModule.telemetry.deviceUpdateInterval
            } else 0

            val mqttEnabled = if (mqttModule?.hasMqtt() == true) {
                mqttModule.mqtt.enabled
            } else false

            """Current Device Configuration:
• Role: ${config.device?.role?.name ?: "UNKNOWN"}
• Rebroadcast: ${config.device?.rebroadcastMode?.name ?: "UNKNOWN"}
• Region: ${config.lora?.region?.name ?: "UNKNOWN"}
• Modem: ${config.lora?.modemPreset?.name ?: "UNKNOWN"}
• Node Info Broadcast: ${config.device?.nodeInfoBroadcastSecs ?: 0}s
• Position Broadcast: ${config.position?.positionBroadcastSecs ?: 0}s
• GPS Mode: ${config.position?.gpsMode?.name ?: "UNKNOWN"}
• Telemetry: ${telemetryInterval}s
• MQTT: $mqttEnabled
• Display: ${config.display?.screenOnSecs ?: 0}s"""
        } else {
            "Configuration not available. Tap 'Refresh' to fetch."
        }
    }

    /**
     * Request device metadata (can be called externally)
     */
    fun requestDeviceMetadataInfo() {
        Log.i(TAG, "Manually requesting device metadata")
        requestDeviceMetadata()
    }

    /**
     * Generate a 32-byte AES256 PSK from a password string
     * Using simple SHA-256 for now to match Meshtastic's expectations
     */
    private fun generatePskFromPassword(password: String): ByteArray {
        // For now, use simple SHA-256 to match what Meshtastic expects
        // Can enhance to PBKDF2 later if Meshtastic supports it
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        return sha256.digest(password.toByteArray(Charsets.UTF_8))
    }
    
    
    // Store password for configuration
    private var channelPassword: String? = null
    
    /**
     * Set the channel password to be used for configuration
     */
    fun setChannelPassword(password: String) {
        channelPassword = password
        Log.i(TAG, "Channel password updated (${password.length} characters)")
    }
    
    
    /**
     * Generate unique packet ID
     */
    private fun getNextPacketId(): Int {
        currentPacketId = (currentPacketId + 1) % Integer.MAX_VALUE
        if (currentPacketId == 0) currentPacketId = 1 // Skip 0 as it may have special meaning
        return currentPacketId
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        scope.cancel()
    }
}
