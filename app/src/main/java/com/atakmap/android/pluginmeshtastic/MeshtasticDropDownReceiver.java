package com.atakmap.android.pluginmeshtastic;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

import com.geeksville.mesh.ChannelProtos;
import com.geeksville.mesh.ConfigProtos;
import com.atakmap.android.pluginmeshtastic.meshtastic.MeshProtos;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.pluginmeshtastic.meshtastic.AtakMeshtasticBridge;
import com.atakmap.android.pluginmeshtastic.meshtastic.LockdownState;
import com.atakmap.android.pluginmeshtastic.meshtastic.MeshtasticBleScanner;
import com.atakmap.android.pluginmeshtastic.meshtastic.MeshtasticManager;
import android.text.InputType;
import android.widget.LinearLayout;
import com.atakmap.android.pluginmeshtastic.plugin.R;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


public class MeshtasticDropDownReceiver extends DropDownReceiver implements DropDown.OnStateListener {

    public static final String TAG = "MeshtasticDropDown";
    public static final String SHOW_MESHTASTIC = "com.atakmap.android.pluginmeshtastic.SHOW_MESHTASTIC";
    
    private final View templateView;
    private final Context pluginContext;
    private final AtakMeshtasticBridge meshtasticBridge;
    private MeshtasticBleScanner bleScanner;
    
    private Button scanButton;
    private Button connectUsbButton;
    private Button disconnectButton;
    private ListView deviceList;
    private TextView statusText;
    private TextView connectionStatus;
    private ProgressBar scanProgress;
    private DeviceListAdapter deviceAdapter;
    
    // Status tab elements
    private TextView connectionType;
    private TextView deviceAddress;
    private TextView deviceName;
    private TextView signalStrength;
    private TextView messagesSent;
    private TextView messagesReceived;
    private TextView connectionTime;
    private View signalInfoCard;
    private View statsCard;
    
    // Tab host
    private TabHost tabHost;
    
    // Settings tab elements
    private EditText channelPasswordField;
    private Button savePasswordButton;

    // Device-configuration UI (Settings tab) - read from device, pushed via "Apply to device"
    private Spinner spinnerRegion, spinnerModemPreset, spinnerRole, spinnerRebroadcastMode,
            spinnerGpsMode, spinnerPairingMode, spinnerDisplayMode;
    private Switch switchUsePreset, switchTxEnabled, switchIgnoreMqtt, switchOverrideDutyCycle,
            switchPositionSmart, switchFixedPosition, switchPowerSaving, switchBtEnabled,
            switchWakeOnTap, switchFlipScreen, switchWifiEnabled, switchEthEnabled,
            switchUplink, switchDownlink;
    private EditText editHopLimit, editNodeInfoBroadcastSecs, editPositionBroadcastSecs,
            editGpsUpdateInterval, editOnBatteryShutdown, editLsSecs, editMinWakeSecs,
            editFixedPin, editScreenOnSecs, editChannelName;
    private Button applyConfigButton, reloadConfigButton;
    private TextView configApplyStatus;
    private TextView settingsFirmwareInfo;

    // Backing enum value lists (parallel to each spinner's displayed names, UNRECOGNIZED removed)
    private List<ConfigProtos.Config.LoRaConfig.RegionCode> regionValues;
    private List<ConfigProtos.Config.LoRaConfig.ModemPreset> modemPresetValues;
    private List<ConfigProtos.Config.DeviceConfig.Role> roleValues;
    private List<ConfigProtos.Config.DeviceConfig.RebroadcastMode> rebroadcastValues;
    private List<ConfigProtos.Config.PositionConfig.GpsMode> gpsModeValues;
    private List<ConfigProtos.Config.BluetoothConfig.PairingMode> pairingModeValues;
    private List<ConfigProtos.Config.DisplayConfig.DisplayMode> displayModeValues;

    // Populate the config fields from the device only once per connection so we don't clobber
    // edits the user is making; "Reload from device" forces a refresh.
    private boolean settingsPopulated = false;

    // Mesh tab (node roster)
    private ListView meshNodeList;
    private TextView meshSummary;
    private MeshNodeAdapter meshAdapter;
    
    // Device metadata elements
    private View deviceMetadataCard;
    private TextView deviceFirmwareVersion;
    private TextView deviceHardwareModel;
    private TextView deviceNodeId;
    private TextView deviceRegion;
    private TextView deviceHasGps;
    private TextView deviceRoles;
    private TextView deviceLastHeard;

    // Device metrics elements
    private View deviceMetricsCard;
    private TextView deviceBatteryLevel;
    private TextView deviceVoltage;
    private TextView deviceUptime;
    private TextView deviceChannelUtilization;
    private TextView deviceAirUtilTx;

    private Handler uiHandler;
    private boolean isScanning = false;
    
    // ATAK Preferences for storing settings (persistent across restarts)
    private static final String PREF_CHANNEL_PASSWORD = "plugin.meshtastic.channel_password";
    private AtakPreferences preferences;
    
    // Store current device info
    private String currentDeviceAddress;
    private String currentDeviceName;
    
    // Track if we've already sent the connection test message
    private boolean testMessageSent = false;
    
    // Track last connection state and metadata update status to avoid spam
    private MeshtasticManager.ConnectionState lastConnectionState = null;
    private boolean metadataUpdateNeeded = true;
    private boolean lastHadNodeInfo = false;
    private long lastDeviceInfoUpdate = 0;
    
    // Cache node ID to avoid repeated formatting calls
    private String cachedNodeIdHex = null;
    private long cachedNodeIdTimestamp = 0;
    
    // RSSI update throttling
    private long lastRssiRequest = 0;
    private static final long RSSI_REQUEST_INTERVAL_MS = 5000; // Request RSSI every 5 seconds

    // Lockdown UI state
    private AlertDialog passphraseDialog;
    private AlertDialog backoffDialog;
    private Runnable backoffTickRunnable;
    private TextView lockdownStatusText;
    private Button lockNowButton;
    private Button enableLockdownButton;
    private Button disableLockdownButton;
    private LockdownState currentLockdownState = LockdownState.None.INSTANCE;

    public MeshtasticDropDownReceiver(MapView mapView, Context context, AtakMeshtasticBridge bridge) {
        super(mapView);
        this.pluginContext = context;
        this.meshtasticBridge = bridge;
        this.uiHandler = new Handler(Looper.getMainLooper());
        
        // Initialize AtakPreferences (use getInstance for proper persistence)
        this.preferences = AtakPreferences.getInstance(pluginContext);
        
        // Inflate the dropdown view
        LayoutInflater inflater = LayoutInflater.from(pluginContext);
        templateView = inflater.inflate(R.layout.meshtastic_layout, null);

        // Initialize BLE scanner
        bleScanner = new MeshtasticBleScanner(mapView.getContext());

        // Initialize UI components
        initializeUI();

        // Start observing scan results immediately to support autoconnect
        observeScanResults();

        // Wire the lockdown UI (passphrase dialog, Lock Now button, status text).
        initializeLockdownUi();
    }

    private void initializeLockdownUi() {
        lockdownStatusText = templateView.findViewById(R.id.lockdown_status_text);
        lockNowButton = templateView.findViewById(R.id.btn_lock_now);
        enableLockdownButton = templateView.findViewById(R.id.btn_enable_lockdown);
        disableLockdownButton = templateView.findViewById(R.id.btn_disable_lockdown);
        if (lockNowButton != null) {
            lockNowButton.setOnClickListener(v -> confirmAndLockNow());
        }
        if (enableLockdownButton != null) {
            enableLockdownButton.setOnClickListener(v -> confirmAndEnableLockdown());
        }
        if (disableLockdownButton != null) {
            disableLockdownButton.setOnClickListener(v -> confirmAndDisableLockdown());
        }
        updateLockdownButtons(currentLockdownState);
        meshtasticBridge.setLockdownListener(state -> uiHandler.post(() -> onLockdownState(state)));
    }

    /**
     * Lockdown is now a client-toggleable mode, so which actions are valid depends on state:
     * Enable only when the device reports DISABLED (capable but off); Disable / Lock Now only
     * when this connection is Unlocked. Everything else (locked, provisioning, no signal) hides
     * them — those flows drive their own dialogs.
     */
    private void updateLockdownButtons(LockdownState state) {
        boolean disabled = state instanceof LockdownState.Disabled;
        boolean unlocked = state instanceof LockdownState.Unlocked;
        if (enableLockdownButton != null) {
            enableLockdownButton.setVisibility(disabled ? View.VISIBLE : View.GONE);
        }
        if (disableLockdownButton != null) {
            disableLockdownButton.setVisibility(unlocked ? View.VISIBLE : View.GONE);
        }
        if (lockNowButton != null) {
            lockNowButton.setVisibility(unlocked ? View.VISIBLE : View.GONE);
        }
    }

    private void onLockdownState(LockdownState state) {
        currentLockdownState = state;
        if (lockdownStatusText != null) {
            lockdownStatusText.setText(formatLockdownStatus(state));
        }
        updateLockdownButtons(state);
        if (state instanceof LockdownState.Disabled) {
            // Lockdown is off (capable but not provisioned, or just disabled). Passive state —
            // no dialog; the operator can turn it on via the Enable button.
            dismissPassphraseDialog();
            dismissBackoffDialog();
        } else if (state instanceof LockdownState.NeedsProvision) {
            dismissBackoffDialog();
            showPassphraseDialog(true);
        } else if (state instanceof LockdownState.Locked) {
            // Locked with any reason — auto-replay happens in the coordinator before we get
            // here, so reaching this state means we need to prompt.
            dismissBackoffDialog();
            showPassphraseDialog(false);
        } else if (state instanceof LockdownState.UnlockFailed) {
            dismissBackoffDialog();
            Toast.makeText(getMapView().getContext(), "Wrong passphrase", Toast.LENGTH_SHORT).show();
            showPassphraseDialog(false);
        } else if (state instanceof LockdownState.UnlockBackoff) {
            dismissPassphraseDialog();
            int seconds = ((LockdownState.UnlockBackoff) state).getBackoffSeconds();
            showBackoffDialog(seconds);
        } else if (state instanceof LockdownState.Unlocked) {
            dismissPassphraseDialog();
            dismissBackoffDialog();
            Toast.makeText(getMapView().getContext(), "Device unlocked", Toast.LENGTH_SHORT).show();
        } else if (state instanceof LockdownState.LockNowAcknowledged) {
            dismissPassphraseDialog();
            dismissBackoffDialog();
            Toast.makeText(getMapView().getContext(), "Device locked — disconnecting", Toast.LENGTH_SHORT).show();
            meshtasticBridge.disconnect();
        }
    }

    private String formatLockdownStatus(LockdownState state) {
        if (state instanceof LockdownState.None) return "Status: not locked";
        if (state instanceof LockdownState.Disabled) return "Status: lockdown off (tap Enable to turn on)";
        if (state instanceof LockdownState.NeedsProvision) return "Status: needs passphrase setup";
        if (state instanceof LockdownState.Locked) {
            String reason = ((LockdownState.Locked) state).getReason();
            return reason.isEmpty() ? "Status: locked" : ("Status: locked (" + reason + ")");
        }
        if (state instanceof LockdownState.UnlockFailed) return "Status: wrong passphrase";
        if (state instanceof LockdownState.UnlockBackoff) {
            return "Status: rate-limited (" + ((LockdownState.UnlockBackoff) state).getBackoffSeconds() + "s)";
        }
        if (state instanceof LockdownState.Unlocked) {
            LockdownState.Unlocked u = (LockdownState.Unlocked) state;
            String exp = u.getValidUntilEpoch() > 0 ? (", until=" + u.getValidUntilEpoch()) : "";
            return "Status: unlocked (boots=" + u.getBootsRemaining() + exp + ")";
        }
        if (state instanceof LockdownState.LockNowAcknowledged) return "Status: locking…";
        return "Status: unknown";
    }

    private void showPassphraseDialog(boolean firstTime) {
        if (passphraseDialog != null && passphraseDialog.isShowing()) return;
        Context dlgCtx = getMapView().getContext();
        LinearLayout container = new LinearLayout(dlgCtx);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * dlgCtx.getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, pad);

        if (firstTime) {
            TextView hint = new TextView(dlgCtx);
            hint.setText("First-time setup — pick a passphrase you can re-enter.");
            container.addView(hint);
        }

        final EditText passField = new EditText(dlgCtx);
        passField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passField.setHint("Passphrase (1–32 chars)");
        container.addView(passField);

        TextView bootsLabel = new TextView(dlgCtx);
        bootsLabel.setText("Boots remaining (0 = firmware default)");
        container.addView(bootsLabel);
        final EditText bootsField = new EditText(dlgCtx);
        bootsField.setInputType(InputType.TYPE_CLASS_NUMBER);
        bootsField.setText("0");
        container.addView(bootsField);

        TextView hoursLabel = new TextView(dlgCtx);
        hoursLabel.setText("Hours valid (0 = no time limit)");
        container.addView(hoursLabel);
        final EditText hoursField = new EditText(dlgCtx);
        hoursField.setInputType(InputType.TYPE_CLASS_NUMBER);
        hoursField.setText("0");
        container.addView(hoursField);

        AlertDialog.Builder b = new AlertDialog.Builder(dlgCtx);
        b.setTitle(firstTime ? "Set device passphrase" : "Unlock device");
        b.setView(container);
        b.setPositiveButton(firstTime ? "Set" : "Unlock", (d, w) -> {
            String pass = passField.getText().toString();
            if (pass.isEmpty() || pass.length() > 32) {
                Toast.makeText(dlgCtx, "Passphrase must be 1–32 bytes", Toast.LENGTH_SHORT).show();
                return;
            }
            int boots = parseIntOrZero(bootsField.getText().toString());
            int hours = parseIntOrZero(hoursField.getText().toString());
            meshtasticBridge.submitLockdownPassphrase(pass, boots, hours, 0);
        });
        b.setNegativeButton("Cancel", null);
        b.setCancelable(false);
        passphraseDialog = b.create();
        passphraseDialog.show();
    }

    private void showBackoffDialog(int initialSeconds) {
        dismissBackoffDialog();
        Context dlgCtx = getMapView().getContext();
        AlertDialog.Builder b = new AlertDialog.Builder(dlgCtx);
        b.setTitle("Rate-limited");
        b.setCancelable(false);
        b.setNegativeButton("Cancel", null);
        backoffDialog = b.create();
        backoffDialog.setMessage("Too many attempts — retry in " + initialSeconds + "s");
        backoffDialog.show();

        final int[] remaining = { initialSeconds };
        backoffTickRunnable = new Runnable() {
            @Override public void run() {
                remaining[0] -= 1;
                if (remaining[0] <= 0) {
                    dismissBackoffDialog();
                    // Re-prompt for passphrase once the backoff has elapsed; the firmware will
                    // tell us again via LOCKDOWN_LOCKED if it's still locked.
                    if (currentLockdownState instanceof LockdownState.UnlockBackoff) {
                        showPassphraseDialog(false);
                    }
                    return;
                }
                if (backoffDialog != null && backoffDialog.isShowing()) {
                    backoffDialog.setMessage("Too many attempts — retry in " + remaining[0] + "s");
                }
                uiHandler.postDelayed(this, 1000);
            }
        };
        uiHandler.postDelayed(backoffTickRunnable, 1000);
    }

    private void dismissPassphraseDialog() {
        if (passphraseDialog != null && passphraseDialog.isShowing()) passphraseDialog.dismiss();
        passphraseDialog = null;
    }

    private void dismissBackoffDialog() {
        if (backoffTickRunnable != null) uiHandler.removeCallbacks(backoffTickRunnable);
        backoffTickRunnable = null;
        if (backoffDialog != null && backoffDialog.isShowing()) backoffDialog.dismiss();
        backoffDialog = null;
    }

    private void confirmAndLockNow() {
        Context dlgCtx = getMapView().getContext();
        new AlertDialog.Builder(dlgCtx)
                .setTitle("Lock device now?")
                .setMessage("This revokes the current session and reboots the device locked. You will need the passphrase to reconnect.")
                .setPositiveButton("Lock", (d, w) -> meshtasticBridge.lockNow())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Operator-initiated ENABLE (client toggle ON). Sends a passphrase to a DISABLED device,
     * which the firmware provisions into lockdown. Guarded with a type-to-confirm because the
     * first enable irreversibly burns the APPROTECT debug-port lockout.
     */
    private void confirmAndEnableLockdown() {
        Context dlgCtx = getMapView().getContext();
        LinearLayout container = new LinearLayout(dlgCtx);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * dlgCtx.getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, pad);

        TextView warn = new TextView(dlgCtx);
        warn.setText("Enabling lockdown encrypts the device's stored config and, on supported "
                + "hardware, PERMANENTLY burns the debug-port (APPROTECT) lockout. That burn is "
                + "IRREVERSIBLE — disabling lockdown later decrypts your data but the debug port "
                + "stays locked for the life of the device.\n\nPick a passphrase you can re-enter; "
                + "if you lose it and the boot token expires, the device is unrecoverable.");
        warn.setTextColor(0xFFFFAAAA);
        container.addView(warn);

        final EditText passField = new EditText(dlgCtx);
        passField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passField.setHint("Passphrase (1–32 chars)");
        container.addView(passField);

        final EditText passConfirmField = new EditText(dlgCtx);
        passConfirmField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passConfirmField.setHint("Re-enter passphrase");
        container.addView(passConfirmField);

        TextView bootsLabel = new TextView(dlgCtx);
        bootsLabel.setText("Boots remaining (0 = firmware default)");
        container.addView(bootsLabel);
        final EditText bootsField = new EditText(dlgCtx);
        bootsField.setInputType(InputType.TYPE_CLASS_NUMBER);
        bootsField.setText("0");
        container.addView(bootsField);

        TextView hoursLabel = new TextView(dlgCtx);
        hoursLabel.setText("Hours valid (0 = no time limit)");
        container.addView(hoursLabel);
        final EditText hoursField = new EditText(dlgCtx);
        hoursField.setInputType(InputType.TYPE_CLASS_NUMBER);
        hoursField.setText("0");
        container.addView(hoursField);

        TextView sessionLabel = new TextView(dlgCtx);
        sessionLabel.setText("Max session seconds (0 = unlimited)");
        container.addView(sessionLabel);
        final EditText sessionField = new EditText(dlgCtx);
        sessionField.setInputType(InputType.TYPE_CLASS_NUMBER);
        sessionField.setText("0");
        container.addView(sessionField);

        TextView confirmLabel = new TextView(dlgCtx);
        confirmLabel.setText("Type ENABLE to confirm the irreversible burn");
        container.addView(confirmLabel);
        final EditText confirmField = new EditText(dlgCtx);
        confirmField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        confirmField.setHint("ENABLE");
        container.addView(confirmField);

        AlertDialog.Builder b = new AlertDialog.Builder(dlgCtx);
        b.setTitle("Enable lockdown");
        b.setView(container);
        b.setNegativeButton("Cancel", null);
        // Wire the positive button manually so validation failures don't dismiss the dialog.
        b.setPositiveButton("Enable lockdown", null);
        final AlertDialog dialog = b.create();
        dialog.setOnShowListener(di -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pass = passField.getText().toString();
            if (pass.isEmpty() || pass.length() > 32) {
                Toast.makeText(dlgCtx, "Passphrase must be 1–32 bytes", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!pass.equals(passConfirmField.getText().toString())) {
                Toast.makeText(dlgCtx, "Passphrases don't match", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!"ENABLE".contentEquals(confirmField.getText().toString().trim())) {
                Toast.makeText(dlgCtx, "Type ENABLE to confirm", Toast.LENGTH_SHORT).show();
                return;
            }
            int boots = parseIntOrZero(bootsField.getText().toString());
            int hours = parseIntOrZero(hoursField.getText().toString());
            int maxSession = parseIntOrZero(sessionField.getText().toString());
            meshtasticBridge.submitLockdownPassphrase(pass, boots, hours, maxSession);
            Toast.makeText(dlgCtx, "Enabling lockdown…", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        }));
        dialog.show();
    }

    /**
     * Operator-initiated DISABLE (client toggle OFF). Requires the current passphrase; the
     * firmware decrypts storage back to plaintext and reboots out of lockdown.
     */
    private void confirmAndDisableLockdown() {
        Context dlgCtx = getMapView().getContext();
        LinearLayout container = new LinearLayout(dlgCtx);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * dlgCtx.getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, pad);

        TextView msg = new TextView(dlgCtx);
        msg.setText("This decrypts the device's stored config back to plaintext and reboots it out "
                + "of lockdown. The one-time APPROTECT debug-port burn is NOT undone. Enter the "
                + "current passphrase to authorize.");
        container.addView(msg);

        final EditText passField = new EditText(dlgCtx);
        passField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passField.setHint("Current passphrase");
        container.addView(passField);

        AlertDialog.Builder b = new AlertDialog.Builder(dlgCtx);
        b.setTitle("Disable lockdown");
        b.setView(container);
        b.setNegativeButton("Cancel", null);
        b.setPositiveButton("Disable lockdown", (d, w) -> {
            String pass = passField.getText().toString();
            if (pass.isEmpty() || pass.length() > 32) {
                Toast.makeText(dlgCtx, "Passphrase must be 1–32 bytes", Toast.LENGTH_SHORT).show();
                return;
            }
            meshtasticBridge.disableLockdown(pass);
            Toast.makeText(dlgCtx, "Disabling lockdown…", Toast.LENGTH_SHORT).show();
        });
        b.show();
    }

    private static int parseIntOrZero(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private void initializeUI() {
        // Set up TabHost
        tabHost = templateView.findViewById(android.R.id.tabhost);
        tabHost.setup();
        
        // Create tabs
        TabHost.TabSpec connectionTab = tabHost.newTabSpec("connection")
                .setIndicator("Connection")
                .setContent(R.id.tab_connection);
        tabHost.addTab(connectionTab);
        
        TabHost.TabSpec statusTab = tabHost.newTabSpec("status")
                .setIndicator("Status")
                .setContent(R.id.tab_status);
        tabHost.addTab(statusTab);

        TabHost.TabSpec meshTab = tabHost.newTabSpec("mesh")
                .setIndicator("Mesh")
                .setContent(R.id.tab_mesh);
        tabHost.addTab(meshTab);

        TabHost.TabSpec settingsTab = tabHost.newTabSpec("settings")
                .setIndicator("Settings")
                .setContent(R.id.tab_settings);
        tabHost.addTab(settingsTab);

        // Set up tab change listener to refresh settings tab when viewed
        tabHost.setOnTabChangedListener(tabId -> {
            if ("settings".equals(tabId)) {
                refreshSettingsTab();
            } else if ("status".equals(tabId)) {
                // Force refresh status tab when switching to it
                updateConnectionStatus();
                updateDeviceInfoForced();
            } else if ("mesh".equals(tabId)) {
                refreshMeshTab();
            }
        });
        
        // Initialize connection tab elements
        scanButton = templateView.findViewById(R.id.btn_scan);
        connectUsbButton = templateView.findViewById(R.id.btn_connect_usb);
        disconnectButton = templateView.findViewById(R.id.btn_disconnect);
        deviceList = templateView.findViewById(R.id.device_list);
        statusText = templateView.findViewById(R.id.status_text);
        connectionStatus = templateView.findViewById(R.id.connection_status);
        scanProgress = templateView.findViewById(R.id.scan_progress);
        
        // Initialize status tab elements
        connectionType = templateView.findViewById(R.id.connection_type);
        deviceAddress = templateView.findViewById(R.id.device_address);
        deviceName = templateView.findViewById(R.id.device_name);
        signalStrength = templateView.findViewById(R.id.signal_strength);
        messagesSent = templateView.findViewById(R.id.messages_sent);
        messagesReceived = templateView.findViewById(R.id.messages_received);
        connectionTime = templateView.findViewById(R.id.connection_time);
        signalInfoCard = templateView.findViewById(R.id.signal_info_card);
        statsCard = templateView.findViewById(R.id.stats_card);
        
        // Initialize settings tab elements
        channelPasswordField = templateView.findViewById(R.id.channel_password);
        savePasswordButton = templateView.findViewById(R.id.btn_save_password);
        
        // Initialize device metadata elements
        deviceMetadataCard = templateView.findViewById(R.id.device_metadata_card);
        deviceFirmwareVersion = templateView.findViewById(R.id.device_firmware_version);
        deviceHardwareModel = templateView.findViewById(R.id.device_hardware_model);
        deviceNodeId = templateView.findViewById(R.id.device_node_id);
        deviceRegion = templateView.findViewById(R.id.device_region);
        deviceHasGps = templateView.findViewById(R.id.device_has_gps);
        deviceRoles = templateView.findViewById(R.id.device_roles);
        deviceLastHeard = templateView.findViewById(R.id.device_last_heard);

        // Initialize device metrics elements
        deviceMetricsCard = templateView.findViewById(R.id.device_metrics_card);
        deviceBatteryLevel = templateView.findViewById(R.id.device_battery_level);
        deviceVoltage = templateView.findViewById(R.id.device_voltage);
        deviceUptime = templateView.findViewById(R.id.device_uptime);
        deviceChannelUtilization = templateView.findViewById(R.id.device_channel_utilization);
        deviceAirUtilTx = templateView.findViewById(R.id.device_air_util_tx);

        // Set up device list adapter
        deviceAdapter = new DeviceListAdapter();
        deviceList.setAdapter(deviceAdapter);
        
        // Set up scan button
        scanButton.setOnClickListener(v -> {
            if (isScanning) {
                stopScan();
            } else {
                startScan();
            }
        });

        // Set up USB connect button
        connectUsbButton.setOnClickListener(v -> {
            connectUsb();
        });

        // Set up disconnect button
        disconnectButton.setOnClickListener(v -> {
            meshtasticBridge.disconnect();
            updateConnectionStatus();
        });
        
        // Set up settings tab
        setupSettingsTab();

        // Set up the device-configuration controls (spinners, apply/reload)
        setupDeviceConfigUi();

        // Set up the Mesh tab node roster
        setupMeshTab();
        
        // Set up device list item click
        deviceList.setOnItemClickListener((parent, view, position, id) -> {
            MeshtasticBleScanner.MeshtasticDevice device = deviceAdapter.getItem(position);
            if (device != null) {
                connectToDevice(device);
            }
        });
        
        updateConnectionStatus();
    }
    
    private void setupSettingsTab() {
        // Load saved password from AtakPreferences
        String savedPassword = preferences.get(PREF_CHANNEL_PASSWORD, "");
        Log.d(TAG, "Loading saved password from AtakPreferences: " + (savedPassword.isEmpty() ? "empty" : savedPassword.length() + " chars"));
        if (channelPasswordField != null) {
            channelPasswordField.setText(savedPassword);
        }
        
        // Set up save password button
        if (savePasswordButton != null) {
            savePasswordButton.setOnClickListener(v -> {
                saveChannelPassword();
            });
        }
        
    }
    
    private void refreshSettingsTab() {
        Log.d(TAG, "Refreshing settings tab");
        
        // Refresh channel password field with latest saved value
        String savedPassword = preferences.get(PREF_CHANNEL_PASSWORD, "");
        if (channelPasswordField != null) {
            channelPasswordField.setText(savedPassword);
        }

        // Firmware/hardware readout — refresh every time the tab opens, since metadata can
        // arrive after the one-time config populate.
        updateFirmwareInfo();

        // Populate the device-configuration fields from the connected device (once per session)
        if (!settingsPopulated) {
            populateSettingsFromDevice();
        }

        // Force update device info when manually switching to settings tab
        updateDeviceInfoForced();
        
        // Note: Don't call updateDeviceMetadata() here as it has its own update logic
        // and is already called by updateConnectionStatus() with anti-spam protection
        
        Log.d(TAG, "Settings tab refreshed - password field updated, device info updated");
    }

    // ============================ Device configuration UI ============================

    private void setupDeviceConfigUi() {
        spinnerRegion = templateView.findViewById(R.id.spinner_region);
        spinnerModemPreset = templateView.findViewById(R.id.spinner_modem_preset);
        spinnerRole = templateView.findViewById(R.id.spinner_role);
        spinnerRebroadcastMode = templateView.findViewById(R.id.spinner_rebroadcast_mode);
        spinnerGpsMode = templateView.findViewById(R.id.spinner_gps_mode);
        spinnerPairingMode = templateView.findViewById(R.id.spinner_pairing_mode);
        spinnerDisplayMode = templateView.findViewById(R.id.spinner_display_mode);

        switchUsePreset = templateView.findViewById(R.id.switch_use_preset);
        switchTxEnabled = templateView.findViewById(R.id.switch_tx_enabled);
        switchIgnoreMqtt = templateView.findViewById(R.id.switch_ignore_mqtt);
        switchOverrideDutyCycle = templateView.findViewById(R.id.switch_override_duty_cycle);
        switchPositionSmart = templateView.findViewById(R.id.switch_position_smart);
        switchFixedPosition = templateView.findViewById(R.id.switch_fixed_position);
        switchPowerSaving = templateView.findViewById(R.id.switch_power_saving);
        switchBtEnabled = templateView.findViewById(R.id.switch_bt_enabled);
        switchWakeOnTap = templateView.findViewById(R.id.switch_wake_on_tap);
        switchFlipScreen = templateView.findViewById(R.id.switch_flip_screen);
        switchWifiEnabled = templateView.findViewById(R.id.switch_wifi_enabled);
        switchEthEnabled = templateView.findViewById(R.id.switch_eth_enabled);
        switchUplink = templateView.findViewById(R.id.switch_uplink);
        switchDownlink = templateView.findViewById(R.id.switch_downlink);

        editHopLimit = templateView.findViewById(R.id.edit_hop_limit);
        editNodeInfoBroadcastSecs = templateView.findViewById(R.id.edit_node_info_broadcast_secs);
        editPositionBroadcastSecs = templateView.findViewById(R.id.edit_position_broadcast_secs);
        editGpsUpdateInterval = templateView.findViewById(R.id.edit_gps_update_interval);
        editOnBatteryShutdown = templateView.findViewById(R.id.edit_on_battery_shutdown);
        editLsSecs = templateView.findViewById(R.id.edit_ls_secs);
        editMinWakeSecs = templateView.findViewById(R.id.edit_min_wake_secs);
        editFixedPin = templateView.findViewById(R.id.edit_fixed_pin);
        editScreenOnSecs = templateView.findViewById(R.id.edit_screen_on_secs);
        editChannelName = templateView.findViewById(R.id.edit_channel_name);

        applyConfigButton = templateView.findViewById(R.id.btn_apply_config);
        reloadConfigButton = templateView.findViewById(R.id.btn_reload_config);
        configApplyStatus = templateView.findViewById(R.id.config_apply_status);
        settingsFirmwareInfo = templateView.findViewById(R.id.settings_firmware_info);

        // Populate the enum spinners straight from the generated proto enums so we always match
        // exactly what the firmware supports (including region codes added in newer firmware).
        regionValues = setupEnumSpinner(spinnerRegion, ConfigProtos.Config.LoRaConfig.RegionCode.values());
        modemPresetValues = setupEnumSpinner(spinnerModemPreset, ConfigProtos.Config.LoRaConfig.ModemPreset.values());
        roleValues = setupEnumSpinner(spinnerRole, ConfigProtos.Config.DeviceConfig.Role.values());
        rebroadcastValues = setupEnumSpinner(spinnerRebroadcastMode, ConfigProtos.Config.DeviceConfig.RebroadcastMode.values());
        gpsModeValues = setupEnumSpinner(spinnerGpsMode, ConfigProtos.Config.PositionConfig.GpsMode.values());
        pairingModeValues = setupEnumSpinner(spinnerPairingMode, ConfigProtos.Config.BluetoothConfig.PairingMode.values());
        displayModeValues = setupEnumSpinner(spinnerDisplayMode, ConfigProtos.Config.DisplayConfig.DisplayMode.values());

        if (reloadConfigButton != null) {
            reloadConfigButton.setOnClickListener(v -> {
                settingsPopulated = false;
                populateSettingsFromDevice();
            });
        }
        if (applyConfigButton != null) {
            applyConfigButton.setOnClickListener(v -> confirmAndApplyConfig());
        }
    }

    /** Build a spinner from a proto enum's values, dropping the synthetic UNRECOGNIZED entry. */
    private <T extends Enum<T>> List<T> setupEnumSpinner(Spinner spinner, T[] values) {
        List<T> list = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (T v : values) {
            if ("UNRECOGNIZED".equals(v.name())) continue;
            list.add(v);
            names.add(v.name());
        }
        if (spinner != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    pluginContext, android.R.layout.simple_spinner_item, names);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);
        }
        return list;
    }

    private <T> void selectSpinner(Spinner spinner, List<T> values, T value) {
        if (spinner == null || values == null || value == null) return;
        int idx = values.indexOf(value);
        if (idx >= 0) spinner.setSelection(idx);
    }

    private <T> T spinnerValue(Spinner spinner, List<T> values, T fallback) {
        if (spinner == null || values == null || values.isEmpty()) return fallback;
        int pos = spinner.getSelectedItemPosition();
        if (pos < 0 || pos >= values.size()) return fallback;
        return values.get(pos);
    }

    private void setChecked(Switch s, boolean checked) {
        if (s != null) s.setChecked(checked);
    }

    private boolean isChecked(Switch s) {
        return s != null && s.isChecked();
    }

    private void setNumber(EditText e, int value) {
        if (e != null) e.setText(String.valueOf(value));
    }

    private int numberOf(EditText e, int fallback) {
        if (e == null) return fallback;
        try {
            String t = e.getText().toString().trim();
            return t.isEmpty() ? fallback : Integer.parseInt(t);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private boolean isConnected() {
        MeshtasticManager.ConnectionState state = meshtasticBridge.getConnectionState();
        return state == MeshtasticManager.ConnectionState.CONNECTED
                || state == MeshtasticManager.ConnectionState.CONFIGURED;
    }

    /** Show the connected device's firmware version + hardware model on the Settings tab. */
    private void updateFirmwareInfo() {
        if (settingsFirmwareInfo == null) return;
        if (!isConnected()) {
            settingsFirmwareInfo.setText("Firmware: — (not connected)");
            return;
        }
        String fw = "Unknown";
        String hw = null;
        try {
            com.geeksville.mesh.MeshProtos.DeviceMetadata md = meshtasticBridge.getDeviceMetadata();
            if (md != null) {
                if (!md.getFirmwareVersion().isEmpty()) fw = md.getFirmwareVersion();
                hw = md.getHwModel().toString();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read device metadata for firmware info: " + e.getMessage());
        }
        StringBuilder sb = new StringBuilder("Firmware: ").append(fw);
        if (hw != null && !hw.isEmpty()) sb.append("  •  HW: ").append(hw);
        settingsFirmwareInfo.setText(sb.toString());
    }

    /** Read the device's current configuration into the Settings fields. */
    private void populateSettingsFromDevice() {
        updateFirmwareInfo();
        if (!isConnected()) {
            if (configApplyStatus != null) {
                configApplyStatus.setText("Connect a device to read and edit its configuration.");
            }
            return;
        }

        ConfigProtos.Config.LoRaConfig lora = meshtasticBridge.getLoraConfig();
        if (lora != null) {
            selectSpinner(spinnerRegion, regionValues, lora.getRegion());
            selectSpinner(spinnerModemPreset, modemPresetValues, lora.getModemPreset());
            setChecked(switchUsePreset, lora.getUsePreset());
            setNumber(editHopLimit, lora.getHopLimit());
            setChecked(switchTxEnabled, lora.getTxEnabled());
            setChecked(switchIgnoreMqtt, lora.getIgnoreMqtt());
            setChecked(switchOverrideDutyCycle, lora.getOverrideDutyCycle());
        }

        ConfigProtos.Config.DeviceConfig dev = meshtasticBridge.getDeviceConfig();
        if (dev != null) {
            selectSpinner(spinnerRole, roleValues, dev.getRole());
            selectSpinner(spinnerRebroadcastMode, rebroadcastValues, dev.getRebroadcastMode());
            setNumber(editNodeInfoBroadcastSecs, dev.getNodeInfoBroadcastSecs());
        }

        ConfigProtos.Config.PositionConfig pos = meshtasticBridge.getPositionConfig();
        if (pos != null) {
            selectSpinner(spinnerGpsMode, gpsModeValues, pos.getGpsMode());
            setNumber(editPositionBroadcastSecs, pos.getPositionBroadcastSecs());
            setNumber(editGpsUpdateInterval, pos.getGpsUpdateInterval());
            setChecked(switchPositionSmart, pos.getPositionBroadcastSmartEnabled());
            setChecked(switchFixedPosition, pos.getFixedPosition());
        }

        ConfigProtos.Config.PowerConfig pow = meshtasticBridge.getPowerConfig();
        if (pow != null) {
            setChecked(switchPowerSaving, pow.getIsPowerSaving());
            setNumber(editOnBatteryShutdown, pow.getOnBatteryShutdownAfterSecs());
            setNumber(editLsSecs, pow.getLsSecs());
            setNumber(editMinWakeSecs, pow.getMinWakeSecs());
        }

        ConfigProtos.Config.BluetoothConfig bt = meshtasticBridge.getBluetoothConfig();
        if (bt != null) {
            setChecked(switchBtEnabled, bt.getEnabled());
            selectSpinner(spinnerPairingMode, pairingModeValues, bt.getMode());
            setNumber(editFixedPin, bt.getFixedPin());
        }

        ConfigProtos.Config.DisplayConfig disp = meshtasticBridge.getDisplayConfig();
        if (disp != null) {
            setNumber(editScreenOnSecs, disp.getScreenOnSecs());
            selectSpinner(spinnerDisplayMode, displayModeValues, disp.getDisplaymode());
            setChecked(switchWakeOnTap, disp.getWakeOnTapOrMotion());
            setChecked(switchFlipScreen, disp.getFlipScreen());
        }

        ConfigProtos.Config.NetworkConfig net = meshtasticBridge.getNetworkConfig();
        if (net != null) {
            setChecked(switchWifiEnabled, net.getWifiEnabled());
            setChecked(switchEthEnabled, net.getEthEnabled());
        }

        ChannelProtos.Channel ch = meshtasticBridge.getPrimaryChannel();
        if (ch != null && ch.hasSettings()) {
            if (editChannelName != null) editChannelName.setText(ch.getSettings().getName());
            setChecked(switchUplink, ch.getSettings().getUplinkEnabled());
            setChecked(switchDownlink, ch.getSettings().getDownlinkEnabled());
        }

        settingsPopulated = true;
        if (configApplyStatus != null) {
            configApplyStatus.setText("Loaded from device. Edit fields, then tap 'Apply to device'.");
        }
    }

    /**
     * A hardened (lockdown) device rejects admin writes until this connection is unlocked.
     * Applying config to a still-locked device would just time out, so block it and tell the
     * operator to unlock first. None (non-hardened / no signal yet) and Unlocked are allowed.
     */
    private boolean isLockdownBlockingWrites() {
        return !(currentLockdownState instanceof LockdownState.None
                || currentLockdownState instanceof LockdownState.Unlocked);
    }

    private void confirmAndApplyConfig() {
        if (!isConnected()) {
            Toast.makeText(pluginContext, "Connect a device first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isLockdownBlockingWrites()) {
            new AlertDialog.Builder(getMapView().getContext())
                    .setTitle("Device locked")
                    .setMessage("This device is in lockdown and won't accept configuration changes "
                            + "until this connection is unlocked. Unlock the device, then apply again.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(getMapView().getContext())
                .setTitle("Apply configuration?")
                .setMessage("Only changed settings are sent to the device. Some changes (role, power, "
                        + "Bluetooth) can reboot the radio and briefly drop this connection.")
                .setPositiveButton("Apply", (d, w) -> applyConfigToDevice())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Build a Config from the device's current sections + the UI edits and push it. */
    private void applyConfigToDevice() {
        ConfigProtos.Config.Builder cfg = ConfigProtos.Config.newBuilder();

        // LoRa
        ConfigProtos.Config.LoRaConfig curLora = meshtasticBridge.getLoraConfig();
        ConfigProtos.Config.LoRaConfig.Builder lb = curLora != null
                ? curLora.toBuilder() : ConfigProtos.Config.LoRaConfig.newBuilder();
        lb.setRegion(spinnerValue(spinnerRegion, regionValues, lb.getRegion()));
        lb.setModemPreset(spinnerValue(spinnerModemPreset, modemPresetValues, lb.getModemPreset()));
        lb.setUsePreset(isChecked(switchUsePreset));
        lb.setHopLimit(numberOf(editHopLimit, lb.getHopLimit()));
        lb.setTxEnabled(isChecked(switchTxEnabled));
        lb.setIgnoreMqtt(isChecked(switchIgnoreMqtt));
        lb.setOverrideDutyCycle(isChecked(switchOverrideDutyCycle));
        cfg.setLora(lb.build());

        // Device
        ConfigProtos.Config.DeviceConfig curDev = meshtasticBridge.getDeviceConfig();
        ConfigProtos.Config.DeviceConfig.Builder db = curDev != null
                ? curDev.toBuilder() : ConfigProtos.Config.DeviceConfig.newBuilder();
        db.setRole(spinnerValue(spinnerRole, roleValues, db.getRole()));
        db.setRebroadcastMode(spinnerValue(spinnerRebroadcastMode, rebroadcastValues, db.getRebroadcastMode()));
        db.setNodeInfoBroadcastSecs(numberOf(editNodeInfoBroadcastSecs, db.getNodeInfoBroadcastSecs()));
        cfg.setDevice(db.build());

        // Position
        ConfigProtos.Config.PositionConfig curPos = meshtasticBridge.getPositionConfig();
        ConfigProtos.Config.PositionConfig.Builder pb = curPos != null
                ? curPos.toBuilder() : ConfigProtos.Config.PositionConfig.newBuilder();
        pb.setGpsMode(spinnerValue(spinnerGpsMode, gpsModeValues, pb.getGpsMode()));
        pb.setPositionBroadcastSecs(numberOf(editPositionBroadcastSecs, pb.getPositionBroadcastSecs()));
        pb.setGpsUpdateInterval(numberOf(editGpsUpdateInterval, pb.getGpsUpdateInterval()));
        pb.setPositionBroadcastSmartEnabled(isChecked(switchPositionSmart));
        pb.setFixedPosition(isChecked(switchFixedPosition));
        cfg.setPosition(pb.build());

        // Power
        ConfigProtos.Config.PowerConfig curPow = meshtasticBridge.getPowerConfig();
        ConfigProtos.Config.PowerConfig.Builder powb = curPow != null
                ? curPow.toBuilder() : ConfigProtos.Config.PowerConfig.newBuilder();
        powb.setIsPowerSaving(isChecked(switchPowerSaving));
        powb.setOnBatteryShutdownAfterSecs(numberOf(editOnBatteryShutdown, powb.getOnBatteryShutdownAfterSecs()));
        powb.setLsSecs(numberOf(editLsSecs, powb.getLsSecs()));
        powb.setMinWakeSecs(numberOf(editMinWakeSecs, powb.getMinWakeSecs()));
        cfg.setPower(powb.build());

        // Bluetooth
        ConfigProtos.Config.BluetoothConfig curBt = meshtasticBridge.getBluetoothConfig();
        ConfigProtos.Config.BluetoothConfig.Builder btb = curBt != null
                ? curBt.toBuilder() : ConfigProtos.Config.BluetoothConfig.newBuilder();
        btb.setEnabled(isChecked(switchBtEnabled));
        btb.setMode(spinnerValue(spinnerPairingMode, pairingModeValues, btb.getMode()));
        btb.setFixedPin(numberOf(editFixedPin, btb.getFixedPin()));
        cfg.setBluetooth(btb.build());

        // Display
        ConfigProtos.Config.DisplayConfig curDisp = meshtasticBridge.getDisplayConfig();
        ConfigProtos.Config.DisplayConfig.Builder dispb = curDisp != null
                ? curDisp.toBuilder() : ConfigProtos.Config.DisplayConfig.newBuilder();
        dispb.setScreenOnSecs(numberOf(editScreenOnSecs, dispb.getScreenOnSecs()));
        dispb.setDisplaymode(spinnerValue(spinnerDisplayMode, displayModeValues, dispb.getDisplaymode()));
        dispb.setWakeOnTapOrMotion(isChecked(switchWakeOnTap));
        dispb.setFlipScreen(isChecked(switchFlipScreen));
        cfg.setDisplay(dispb.build());

        // Network
        ConfigProtos.Config.NetworkConfig curNet = meshtasticBridge.getNetworkConfig();
        ConfigProtos.Config.NetworkConfig.Builder netb = curNet != null
                ? curNet.toBuilder() : ConfigProtos.Config.NetworkConfig.newBuilder();
        netb.setWifiEnabled(isChecked(switchWifiEnabled));
        netb.setEthEnabled(isChecked(switchEthEnabled));
        cfg.setNetwork(netb.build());

        // Channel — only apply if the user changed name/uplink/downlink or entered a new password.
        String uiName = editChannelName != null ? editChannelName.getText().toString() : "";
        boolean uiUp = isChecked(switchUplink);
        boolean uiDown = isChecked(switchDownlink);
        String password = channelPasswordField != null ? channelPasswordField.getText().toString().trim() : "";
        boolean channelChanged = !password.isEmpty();
        ChannelProtos.Channel ch = meshtasticBridge.getPrimaryChannel();
        if (ch != null && ch.hasSettings()) {
            ChannelProtos.ChannelSettings s = ch.getSettings();
            if (!s.getName().equals(uiName) || s.getUplinkEnabled() != uiUp || s.getDownlinkEnabled() != uiDown) {
                channelChanged = true;
            }
        } else if (!uiName.isEmpty() || uiUp || uiDown) {
            channelChanged = true;
        }

        if (configApplyStatus != null) configApplyStatus.setText("Applying to device…");
        if (applyConfigButton != null) applyConfigButton.setEnabled(false);

        meshtasticBridge.applyUserConfig(cfg.build(), channelChanged, uiName, password, uiUp, uiDown,
                success -> uiHandler.post(() -> {
                    if (applyConfigButton != null) applyConfigButton.setEnabled(true);
                    Toast.makeText(pluginContext,
                            success ? "Configuration applied" : "Some settings failed to apply",
                            Toast.LENGTH_LONG).show();
                    if (configApplyStatus != null) {
                        configApplyStatus.setText(success
                                ? "Applied. The device may reboot; reconnect if it drops."
                                : "Apply failed — check the connection and try again.");
                    }
                    // Force a fresh read next time the tab is opened so the UI reflects the device.
                    settingsPopulated = false;
                }));
    }

    private void updateDeviceInfoForced() {
        // Reset throttle timer to force update
        lastDeviceInfoUpdate = 0;
        updateDeviceInfo();
    }
    
    private void updateDeviceInfo() {
        // Throttle updates to prevent spam (max once per 1 second, less aggressive than before)
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastDeviceInfoUpdate < 1000) {
            Log.d(TAG, "Throttling device info update - too soon since last update");
            return;
        }
        lastDeviceInfoUpdate = currentTime;
        
        Log.d(TAG, "Updating device info fields (callback-driven)");
        
        // Update Node ID - only when connected & configured to avoid excessive calls
        if (deviceNodeId != null) {
            MeshtasticManager.ConnectionState state = meshtasticBridge.getConnectionState();
            String nodeIdHex = getCachedNodeIdHex(state);
            
            if (nodeIdHex != null) {
                deviceNodeId.setText("Node ID: " + nodeIdHex);
                Log.d(TAG, "Displayed Node ID: " + nodeIdHex);
            } else if (currentDeviceAddress != null) {
                deviceNodeId.setText("Node ID: " + currentDeviceAddress + " (BT)");
                Log.d(TAG, "Displayed BT address as fallback: " + currentDeviceAddress);
            } else {
                deviceNodeId.setText("Node ID: Not available");
                Log.d(TAG, "No Node ID available to display");
            }
        }
        
        // Show metadata card if we have any device info to display
        // Note: Card visibility will be managed by the callback system when data is actually received
    }
    
    private void saveChannelPassword() {
        if (channelPasswordField == null) return;
        
        String password = channelPasswordField.getText().toString().trim();
        
        // Save to AtakPreferences (persistent across restarts)
        preferences.set(PREF_CHANNEL_PASSWORD, password);
        Log.d(TAG, "Saving channel password to AtakPreferences: " + (password.isEmpty() ? "empty" : password.length() + " chars"));
        
        // Verify it was saved
        String savedPassword = preferences.get(PREF_CHANNEL_PASSWORD, "");
        Log.d(TAG, "Verification - saved password: " + (savedPassword.isEmpty() ? "empty" : savedPassword.length() + " chars"));
        
        // Show confirmation
        Toast.makeText(pluginContext, "Channel password saved", Toast.LENGTH_SHORT).show();
        
        // If connected, apply the password now
        if (meshtasticBridge.getConnectionState() == MeshtasticManager.ConnectionState.CONNECTED ||
            meshtasticBridge.getConnectionState() == MeshtasticManager.ConnectionState.CONFIGURED) {
            applyChannelPassword(password);
        }
    }
    
    private void updateDeviceMetadata() {
        MeshtasticManager.ConnectionState currentState = meshtasticBridge != null ? meshtasticBridge.getConnectionState() : null;
        Log.d(TAG, "updateDeviceMetadata called - Connection state: " + currentState);
        
        if (meshtasticBridge == null || 
            (currentState != MeshtasticManager.ConnectionState.CONNECTED &&
             currentState != MeshtasticManager.ConnectionState.CONFIGURED)) {
            // Hide metadata card when not connected
            Log.d(TAG, "Hiding metadata card - not connected (state: " + currentState + ")");
            if (deviceMetadataCard != null) {
                deviceMetadataCard.setVisibility(View.GONE);
            }
            return;
        }
        
        // Show metadata card when connected
        Log.d(TAG, "Showing metadata card - connected (state: " + currentState + ")");
        if (deviceMetadataCard != null) {
            deviceMetadataCard.setVisibility(View.VISIBLE);
        }
        
        // Get device metadata from the bridge
        try {
            com.geeksville.mesh.MeshProtos.DeviceMetadata metadata = meshtasticBridge.getDeviceMetadata();
            
            if (metadata != null) {
                Log.d(TAG, "Device metadata retrieved and displaying");
                
                // Node ID (only get from MyNodeInfo when configured to avoid excessive calls)
                if (deviceNodeId != null) {
                    String nodeIdHex = getCachedNodeIdHex(currentState);
                    if (nodeIdHex != null) {
                        deviceNodeId.setText("Node ID: " + nodeIdHex);
                    } else if (currentDeviceAddress != null) {
                        deviceNodeId.setText("Node ID: " + currentDeviceAddress + " (BT)");
                    } else {
                        deviceNodeId.setText("Node ID: Unknown");
                    }
                }
                
                // Firmware version
                if (deviceFirmwareVersion != null) {
                    String firmware = metadata.getFirmwareVersion();
                    deviceFirmwareVersion.setText("Firmware: " + (firmware.isEmpty() ? "Unknown" : firmware));
                }
                
                // Hardware model
                if (deviceHardwareModel != null) {
                    String hwModel = metadata.getHwModel().toString();
                    deviceHardwareModel.setText("Hardware: " + hwModel);
                }
                
                // Role
                if (deviceRoles != null) {
                    String role = metadata.getRole().toString();
                    deviceRoles.setText("Role: " + role);
                }
                
                // GPS capability (check position flags for GPS capability)
                if (deviceHasGps != null) {
                    boolean hasGps = metadata.getPositionFlags() > 0;
                    deviceHasGps.setText("GPS: " + (hasGps ? "Yes" : "No"));
                }
                
                // Region - get from LoRa config
                if (deviceRegion != null) {
                    try {
                        com.geeksville.mesh.ConfigProtos.Config.LoRaConfig loraConfig = meshtasticBridge.getLoraConfig();
                        if (loraConfig != null && loraConfig.getRegion() != null) {
                            String regionName = loraConfig.getRegion().toString();
                            deviceRegion.setText("Region: " + regionName);
                        } else {
                            deviceRegion.setText("Region: Not configured");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error getting LoRa region: " + e.getMessage());
                        deviceRegion.setText("Region: N/A");
                    }
                }
                
                if (deviceLastHeard != null) {
                    deviceLastHeard.setText("Last Heard: Just now");
                }
                
            } else {
                Log.d(TAG, "Device metadata not available yet, showing retrieving status");
                // Show retrieving status when metadata is not yet available
                if (deviceNodeId != null) {
                    String nodeIdHex = getCachedNodeIdHex(currentState);
                    if (nodeIdHex != null) {
                        deviceNodeId.setText("Node ID: " + nodeIdHex);
                    } else if (currentDeviceAddress != null) {
                        deviceNodeId.setText("Node ID: " + currentDeviceAddress + " (BT)");
                    } else {
                        deviceNodeId.setText("Node ID: Retrieving...");
                    }
                }
                
                if (deviceFirmwareVersion != null) {
                    deviceFirmwareVersion.setText("Firmware: Retrieving...");
                }
                
                if (deviceHardwareModel != null) {
                    deviceHardwareModel.setText("Hardware: " + (currentDeviceName != null ? currentDeviceName : "Unknown"));
                }
                
                if (deviceRegion != null) {
                    // Try to get region from LoRa config even when metadata is not available
                    try {
                        com.geeksville.mesh.ConfigProtos.Config.LoRaConfig loraConfig = meshtasticBridge.getLoraConfig();
                        if (loraConfig != null && loraConfig.getRegion() != null) {
                            String regionName = loraConfig.getRegion().toString();
                            deviceRegion.setText("Region: " + regionName);
                        } else {
                            deviceRegion.setText("Region: Retrieving...");
                        }
                    } catch (Exception e) {
                        deviceRegion.setText("Region: Retrieving...");
                    }
                }
                
                if (deviceHasGps != null) {
                    deviceHasGps.setText("GPS: Checking...");
                }
                
                if (deviceRoles != null) {
                    deviceRoles.setText("Role: Retrieving...");
                }
                
                if (deviceLastHeard != null) {
                    deviceLastHeard.setText("Last Heard: Just now");
                }
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Error updating device metadata: " + e.getMessage());
            // Set all fields to N/A on error
            if (deviceNodeId != null) deviceNodeId.setText("Node ID: N/A");
            if (deviceFirmwareVersion != null) deviceFirmwareVersion.setText("Firmware: N/A");
            if (deviceHardwareModel != null) deviceHardwareModel.setText("Hardware: N/A");
            if (deviceRegion != null) deviceRegion.setText("Region: N/A");
            if (deviceHasGps != null) deviceHasGps.setText("GPS: N/A");
            if (deviceRoles != null) deviceRoles.setText("Role: N/A");
            if (deviceLastHeard != null) deviceLastHeard.setText("Last Heard: N/A");
        }
    }

    private void updateDeviceMetrics() {
        MeshtasticManager.ConnectionState currentState = meshtasticBridge != null ? meshtasticBridge.getConnectionState() : null;

        if (meshtasticBridge == null ||
            (currentState != MeshtasticManager.ConnectionState.CONNECTED &&
             currentState != MeshtasticManager.ConnectionState.CONFIGURED)) {
            // Hide metrics card when not connected
            Log.d(TAG, "Hiding device metrics card - not connected (state: " + currentState + ")");
            if (deviceMetricsCard != null) {
                deviceMetricsCard.setVisibility(View.GONE);
            }
            return;
        }

        // Get device metrics from the bridge
        try {
            com.geeksville.mesh.TelemetryProtos.DeviceMetrics metrics = meshtasticBridge.getCurrentDeviceMetrics();

            if (metrics != null) {

                // Show metrics card when data is available
                if (deviceMetricsCard != null) {
                    deviceMetricsCard.setVisibility(View.VISIBLE);
                }

                // Update battery level
                if (deviceBatteryLevel != null) {
                    if (metrics.hasBatteryLevel()) {
                        int batteryLevel = metrics.getBatteryLevel();
                        String batteryText = "Battery: " + batteryLevel + "%";
                        if (batteryLevel > 100) {
                            batteryText += " (Powered)";
                        }
                        deviceBatteryLevel.setText(batteryText);
                    } else {
                        deviceBatteryLevel.setText("Battery: N/A");
                    }
                }

                // Update voltage
                if (deviceVoltage != null) {
                    if (metrics.hasVoltage()) {
                        float voltage = metrics.getVoltage();
                        deviceVoltage.setText(String.format("Voltage: %.2fV", voltage));
                    } else {
                        deviceVoltage.setText("Voltage: N/A");
                    }
                }

                // Update uptime
                if (deviceUptime != null) {
                    if (metrics.hasUptimeSeconds()) {
                        int uptimeSeconds = metrics.getUptimeSeconds();
                        String uptimeText = formatUptime(uptimeSeconds);
                        deviceUptime.setText("Uptime: " + uptimeText);
                    } else {
                        deviceUptime.setText("Uptime: N/A");
                    }
                }

                // Update channel utilization
                if (deviceChannelUtilization != null) {
                    if (metrics.hasChannelUtilization()) {
                        float channelUtil = metrics.getChannelUtilization();
                        deviceChannelUtilization.setText(String.format("Channel Utilization: %.1f%%", channelUtil));
                    } else {
                        deviceChannelUtilization.setText("Channel Utilization: N/A");
                    }
                }

                // Update air utilization TX
                if (deviceAirUtilTx != null) {
                    if (metrics.hasAirUtilTx()) {
                        float airUtilTx = metrics.getAirUtilTx();
                        deviceAirUtilTx.setText(String.format("Air Util TX: %.1f%%", airUtilTx));
                    } else {
                        deviceAirUtilTx.setText("Air Util TX: N/A");
                    }
                }
            } else {
                // Hide card if metrics are not available
                if (deviceMetricsCard != null) {
                    deviceMetricsCard.setVisibility(View.GONE);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error updating device metrics: " + e.getMessage());
            // Hide card on error
            if (deviceMetricsCard != null) {
                deviceMetricsCard.setVisibility(View.GONE);
            }
        }
    }

    private String formatUptime(int uptimeSeconds) {
        int days = uptimeSeconds / 86400;
        int hours = (uptimeSeconds % 86400) / 3600;
        int minutes = (uptimeSeconds % 3600) / 60;

        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours, minutes);
        } else if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else {
            return String.format("%dm", minutes);
        }
    }

    private void applyChannelPassword(String password) {
        if (password.isEmpty()) {
            Log.d(TAG, "Channel password is empty, skipping PSK configuration");
            return;
        }
        
        Log.d(TAG, "Applying channel password to connected device");
        // This will be implemented when we add the method to MeshtasticManager
        meshtasticBridge.setChannelPassword(password);
    }
    
    public String getChannelPassword() {
        return preferences.get(PREF_CHANNEL_PASSWORD, "");
    }
    
    private void observeScanResults() {
        // This would normally use Kotlin coroutines, but since we're in Java,
        // we'll poll the state periodically
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (bleScanner != null) {
                    // Update device list
                    Map<String, MeshtasticBleScanner.MeshtasticDevice> devices = 
                        bleScanner.getDiscoveredDevices().getValue();
                    deviceAdapter.updateDevices(devices);
                    
                    // Update scanning state
                    boolean scanning = bleScanner.getScanningState().getValue();
                    if (scanning != isScanning) {
                        isScanning = scanning;
                        updateScanButton();
                    }
                    
                    // Check for errors
                    String error = bleScanner.getScanError().getValue();
                    if (error != null) {
                        showError(error);
                    }
                }
                
                // Update connection status - this should always happen to support autoconnect
                updateConnectionStatus();

                // Keep the Mesh roster live while that tab is the one on screen.
                if (isVisible() && tabHost != null && "mesh".equals(tabHost.getCurrentTabTag())) {
                    refreshMeshTab();
                }

                // Continue polling - poll more frequently when visible and connected
                if (isVisible()) {
                    // Poll faster when connected to keep status fresh
                    MeshtasticManager.ConnectionState state = meshtasticBridge != null ? 
                        meshtasticBridge.getConnectionState() : MeshtasticManager.ConnectionState.DISCONNECTED;
                    
                    if (state == MeshtasticManager.ConnectionState.CONNECTED || 
                        state == MeshtasticManager.ConnectionState.CONFIGURED) {
                        uiHandler.postDelayed(this, 500); // Update every 500ms when connected and visible
                    } else {
                        uiHandler.postDelayed(this, 1000); // Update every second when visible but not connected
                    }
                } else {
                    // Keep polling at a slower rate when not visible to support autoconnect status updates
                    uiHandler.postDelayed(this, 5000); // Update every 5 seconds when not visible
                }
            }
        }, 1000);
    }
    
    /**
     * Get cached node ID hex to avoid repeated formatting calls.
     * Once retrieved for a configured session, the node ID should not change.
     */
    private String getCachedNodeIdHex(MeshtasticManager.ConnectionState currentState) {
        // Only get node ID when configured
        if (currentState != MeshtasticManager.ConnectionState.CONFIGURED) {
            cachedNodeIdHex = null;
            cachedNodeIdTimestamp = 0;
            return null;
        }
        
        // Return cached value if available - node ID doesn't change during a session
        if (cachedNodeIdHex != null) {
            return cachedNodeIdHex;
        }
        
        // Cache is empty, get fresh value (should only happen once per configured session)
        String nodeIdHex = meshtasticBridge.getMyNodeIdHex();
        if (nodeIdHex != null) {
            cachedNodeIdHex = nodeIdHex;
            cachedNodeIdTimestamp = System.currentTimeMillis();
            Log.d(TAG, "Cached Node ID for session: " + nodeIdHex);
        }
        
        return nodeIdHex;
    }
    
    private void startScan() {
        Log.d(TAG, "Starting BLE scan");
        
        // Check permissions
        if (!bleScanner.hasRequiredPermissions()) {
            showPermissionDialog();
            return;
        }
        
        // Check if Bluetooth is enabled
        if (!bleScanner.isBluetoothEnabled()) {
            showError("Please enable Bluetooth");
            return;
        }
        
        // Clear previous results
        deviceAdapter.clearDevices();
        statusText.setText("Scanning for Meshtastic devices...");
        scanProgress.setVisibility(View.VISIBLE);
        
        // Start scan
        bleScanner.startScan();
        isScanning = true;
        updateScanButton();
    }
    
    private void stopScan() {
        Log.d(TAG, "Stopping BLE scan");
        bleScanner.stopScan();
        isScanning = false;
        updateScanButton();
        scanProgress.setVisibility(View.GONE);
        statusText.setText("Scan stopped");
    }
    
    private void updateScanButton() {
        if (isScanning) {
            scanButton.setText("Stop Scan");
        } else {
            scanButton.setText("Scan for Devices");
        }
    }
    
    private void connectToDevice(MeshtasticBleScanner.MeshtasticDevice device) {
        Log.d(TAG, "Connecting to device: " + device.getName() + " (" + device.getAddress() + ")");

        // Store device info for status display
        currentDeviceAddress = device.getAddress();
        currentDeviceName = device.getName();

        // Also store in preferences for future auto-reconnection using ATAK preferences
        String prefKey = "plugin.meshtastic.last_device_name_" + device.getAddress();
        preferences.set(prefKey, device.getName());
        Log.d(TAG, "Stored device name for future auto-reconnection: " + device.getName());

        // Show connecting dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(getMapView().getContext());
        builder.setTitle("Connecting");
        builder.setMessage("Connecting to " + device.getName() + "...");
        builder.setCancelable(false);
        AlertDialog dialog = builder.create();
        dialog.show();

        // Stop scanning
        stopScan();

        // Connect to device
        meshtasticBridge.connectBluetooth(device.getAddress());

        // Dismiss dialog after a short delay
        uiHandler.postDelayed(() -> {
            dialog.dismiss();
            updateConnectionStatus();
        }, 2000);
    }

    private void connectUsb() {
        Log.d(TAG, "Attempting to connect via USB");

        // Check if bridge is properly initialized
        if (meshtasticBridge == null) {
            Log.e(TAG, "MeshtasticBridge is null, cannot connect via USB");
            showError("Plugin not properly initialized. Please restart ATAK.");
            return;
        }

        // Store device info for status display (USB doesn't have address/name like Bluetooth)
        currentDeviceAddress = "USB";
        currentDeviceName = "USB Device";

        // Show connecting dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(getMapView().getContext());
        builder.setTitle("Connecting");
        builder.setMessage("Connecting via USB...\n\nMake sure your device is connected via USB cable and you have granted USB permissions.");
        builder.setCancelable(false);
        AlertDialog dialog = builder.create();
        dialog.show();

        // Stop scanning if active
        if (isScanning) {
            stopScan();
        }

        // Update status to show connecting
        statusText.setText("Connecting via USB...");

        try {
            // Connect via USB (empty device path means auto-detect)
            meshtasticBridge.connectUsb("");
        } catch (Exception e) {
            Log.e(TAG, "Error connecting via USB: " + e.getMessage(), e);
            dialog.dismiss();
            showError("USB connection failed: " + e.getMessage());
            return;
        }

        // Dismiss dialog after a short delay
        uiHandler.postDelayed(() -> {
            dialog.dismiss();
            updateConnectionStatus();
        }, 3000); // Longer delay for USB connection
    }
    
    private void updateConnectionStatus() {
        MeshtasticManager.ConnectionState state = meshtasticBridge.getConnectionState();
        
        // Check if connection state has changed or if metadata update is needed
        boolean stateChanged = (lastConnectionState != state);
        // Only check for node info when connected & configured to avoid excessive calls
        boolean hasNodeInfo = false;
        if (state == MeshtasticManager.ConnectionState.CONFIGURED) {
            hasNodeInfo = (getCachedNodeIdHex(state) != null);
        }
        boolean nodeInfoChanged = (lastHadNodeInfo != hasNodeInfo);
        boolean shouldUpdateMetadata = stateChanged || metadataUpdateNeeded || nodeInfoChanged;
        
        String statusMessage;
        String connectionTypeText;
        int statusColor;
        
        // Determine connection type based on current device info
        boolean isUsbConnection = "USB".equals(currentDeviceAddress);
        String connType = isUsbConnection ? "USB Serial" : "Bluetooth LE";

        switch (state) {
            case CONNECTED:
                statusMessage = "Connected";
                connectionTypeText = connType;
                statusColor = 0xFF00FF00; // Green
                disconnectButton.setEnabled(true);

                // Show status tab cards
                if (signalInfoCard != null) {
                    // Show signal info for Bluetooth, hide for USB (no RSSI)
                    signalInfoCard.setVisibility(isUsbConnection ? View.GONE : View.VISIBLE);
                }
                if (statsCard != null) statsCard.setVisibility(View.VISIBLE);

                // Force device info update when newly connected
                if (stateChanged) {
                    Log.d(TAG, "State changed to CONNECTED, forcing UI updates");
                    updateDeviceInfoForced();
                    // Force metadata update on connection
                    metadataUpdateNeeded = true;
                }
                break;

            case CONNECTING:
                statusMessage = "Connecting...";
                connectionTypeText = connType + " (Connecting)";
                statusColor = 0xFFFFFF00; // Yellow
                disconnectButton.setEnabled(false);

                // Hide status tab cards while connecting
                if (signalInfoCard != null) signalInfoCard.setVisibility(View.GONE);
                if (statsCard != null) statsCard.setVisibility(View.GONE);
                break;

            case CONFIGURED:
                statusMessage = "Connected & Configured";
                connectionTypeText = connType + " (Ready)";
                statusColor = 0xFF00FF00; // Green
                disconnectButton.setEnabled(true);

                // Show status tab cards
                if (signalInfoCard != null) {
                    // Show signal info for Bluetooth, hide for USB (no RSSI)
                    signalInfoCard.setVisibility(isUsbConnection ? View.GONE : View.VISIBLE);
                }
                if (statsCard != null) statsCard.setVisibility(View.VISIBLE);

                // Force device info update when newly configured
                if (stateChanged) {
                    Log.d(TAG, "State changed to CONFIGURED, forcing UI updates");
                    updateDeviceInfoForced();
                    // Force metadata update on configuration
                    metadataUpdateNeeded = true;
                }
                break;
                
            case DISCONNECTED:
            default:
                statusMessage = "Disconnected";
                connectionTypeText = "Disconnected";
                statusColor = 0xFFFF0000; // Red
                disconnectButton.setEnabled(false);
                
                // Clear device info when disconnected
                currentDeviceAddress = null;
                currentDeviceName = null;
                
                // Clear cached node ID when disconnected
                cachedNodeIdHex = null;
                cachedNodeIdTimestamp = 0;
                
                // Reset test message flag for next connection
                testMessageSent = false;

                // Re-read device configuration into the Settings tab on the next connection
                settingsPopulated = false;

                // Hide status tab cards and device metadata when disconnected
                if (signalInfoCard != null) signalInfoCard.setVisibility(View.GONE);
                if (statsCard != null) statsCard.setVisibility(View.GONE);
                
                // Hide metadata card when disconnected - but only if state actually changed
                if (stateChanged && deviceMetadataCard != null) {
                    deviceMetadataCard.setVisibility(View.GONE);
                    metadataUpdateNeeded = true; // Reset for next connection
                }
                break;
        }
        
        // Update connection tab status
        connectionStatus.setText("Status: " + statusMessage);
        connectionStatus.setTextColor(statusColor);
        
        // Update status tab details
        if (connectionType != null) {
            connectionType.setText("Type: " + connectionTypeText);
            connectionType.setTextColor(statusColor);
        }
        
        // For auto-reconnection, try to get device info from saved preferences
        if (currentDeviceAddress == null && (state == MeshtasticManager.ConnectionState.CONNECTED || 
                                             state == MeshtasticManager.ConnectionState.CONFIGURED)) {
            // Auto-connection happened, get device info from bridge or preferences
            String lastConnectionInfo = meshtasticBridge.getLastConnectionInfo();
            if (lastConnectionInfo != null && lastConnectionInfo.startsWith("Bluetooth:")) {
                // Parse the connection info: "Bluetooth: name (address)"
                try {
                    String[] parts = lastConnectionInfo.substring(10).trim().split("\\(");  // Remove "Bluetooth: "
                    if (parts.length == 2) {
                        currentDeviceName = parts[0].trim();
                        currentDeviceAddress = parts[1].replace(")", "").trim();
                        Log.d(TAG, "Auto-connection detected, restored device info from bridge: " + currentDeviceName + " (" + currentDeviceAddress + ")");
                        
                        // Force metadata update since this is a new connection with restored info
                        metadataUpdateNeeded = true;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to parse last connection info: " + lastConnectionInfo);
                }
            }
            
            // Alternative: try to get from stored preferences if bridge info is not available
            if (currentDeviceAddress == null) {
                // Try to get from ATAK preferences using the stored Bluetooth address
                String storedAddress = preferences.get("plugin.meshtastic.last_bluetooth_address", null);
                if (storedAddress != null) {
                    currentDeviceAddress = storedAddress;
                    String prefKey = "plugin.meshtastic.last_device_name_" + storedAddress;
                    currentDeviceName = preferences.get(prefKey, "Meshtastic Device");
                    Log.d(TAG, "Auto-connection detected, restored device info from preferences: " + currentDeviceName + " (" + currentDeviceAddress + ")");
                    
                    // Force metadata update since this is a new connection with restored info
                    metadataUpdateNeeded = true;
                }
            }
        }
        
        if (deviceAddress != null) {
            deviceAddress.setText("Address: " + (currentDeviceAddress != null ? currentDeviceAddress : "N/A"));
        }
        
        if (deviceName != null) {
            deviceName.setText("Name: " + (currentDeviceName != null ? currentDeviceName : "N/A"));
        }

        // Update signal strength if connected via Bluetooth - skip for USB
        if (signalStrength != null) {
            if ((state == MeshtasticManager.ConnectionState.CONNECTED ||
                 state == MeshtasticManager.ConnectionState.CONFIGURED) && !isUsbConnection) {
                try {
                    // Throttled RSSI requests to avoid spamming the Bluetooth interface
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastRssiRequest > RSSI_REQUEST_INTERVAL_MS) {
                        meshtasticBridge.requestRssi(); // Trigger a fresh RSSI reading
                        lastRssiRequest = currentTime;
                    }

                    // Always get current cached RSSI value
                    int rssiValue = meshtasticBridge.getCurrentRssi();

                    if (rssiValue < 0) { // RSSI values are negative (e.g., -60 dBm)
                        // Show actual RSSI value
                        signalStrength.setText("RSSI: " + rssiValue + " dBm");
                    } else if (rssiValue == 0) {
                        // RSSI not available yet
                        signalStrength.setText("RSSI: Reading...");
                    } else {
                        // Shouldn't happen, but handle positive values
                        signalStrength.setText("RSSI: " + rssiValue + " dBm");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to get RSSI: " + e.getMessage());
                    signalStrength.setText("RSSI: Unknown");
                }
            } else if (isUsbConnection) {
                signalStrength.setText("RSSI: N/A (USB)");
            } else {
                signalStrength.setText("RSSI: N/A");
            }
        }
        
        // Update connection time if connected
        if (connectionTime != null) {
            if (state == MeshtasticManager.ConnectionState.CONNECTED || 
                state == MeshtasticManager.ConnectionState.CONFIGURED) {
                // Show current status instead of hardcoded placeholder
                connectionTime.setText("Connected: Active");
            } else {
                connectionTime.setText("Connected: N/A");
            }
        }
        
        // Update message counters with actual stats from bridge
        if (messagesSent != null || messagesReceived != null) {
            try {
                // Get actual message statistics from the bridge including received count
                kotlin.Triple<Integer, Integer, Integer> stats = meshtasticBridge.getDetailedMessageStats();
                if (messagesSent != null) {
                    messagesSent.setText("Messages Sent: " + stats.component1());
                }
                if (messagesReceived != null) {
                    messagesReceived.setText("Messages Received: " + stats.component3());
                }
            } catch (Exception e) {
                // Fallback to default values
                if (messagesSent != null) {
                    messagesSent.setText("Messages Sent: 0");
                }
                if (messagesReceived != null) {
                    messagesReceived.setText("Messages Received: 0");
                }
            }
        }
        
        // Update device metadata if needed
        if (shouldUpdateMetadata) {
            Log.d(TAG, "Calling updateDeviceMetadata() - shouldUpdateMetadata is true");
            updateDeviceMetadata();
            metadataUpdateNeeded = false; // Reset flag after update
        }

        // Update device metrics (always try to update when connected)
        if (state == MeshtasticManager.ConnectionState.CONNECTED ||
            state == MeshtasticManager.ConnectionState.CONFIGURED) {
            updateDeviceMetrics();
        }

        // Update last known connection state and node info status
        lastConnectionState = state;
        lastHadNodeInfo = hasNodeInfo;
    }
    
    private void showError(String message) {
        Toast.makeText(pluginContext, message, Toast.LENGTH_LONG).show();
        statusText.setText("Error: " + message);
    }
    
    private void showPermissionDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getMapView().getContext());
        builder.setTitle("Permissions Required");
        builder.setMessage("Bluetooth permissions are required to scan for Meshtastic devices. Please grant the necessary permissions in Settings.");
        builder.setPositiveButton("OK", null);
        builder.show();
    }


    @Override
    protected void disposeImpl() {
        if (bleScanner != null) {
            bleScanner.cleanup();
        }
        if (meshtasticBridge != null) {
            meshtasticBridge.setLockdownListener(null);
        }
        dismissPassphraseDialog();
        dismissBackoffDialog();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (SHOW_MESHTASTIC.equals(action)) {
            showDropDown(templateView, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH,
                    HALF_HEIGHT, false, this);
        }
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean v) {
        if (v) {
            // Immediately update status when dropdown becomes visible
            updateConnectionStatus();
            // Resume rapid updates when visible (observeScanResults is already running)
        } else {
            // Stop scanning when hidden, but keep status polling running
            if (isScanning) {
                stopScan();
            }
        }
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
        if (isScanning) {
            stopScan();
        }
    }
    
    /**
     * Called when auto-reconnection completes successfully
     * This ensures UI is updated properly for auto-connections
     */
    public void onAutoReconnectionComplete() {
        Log.d(TAG, "Auto-reconnection completed, forcing complete UI refresh");
        uiHandler.post(() -> {
            // Clear cached device info to force fresh lookup
            currentDeviceAddress = null;
            currentDeviceName = null;
            metadataUpdateNeeded = true;
            
            // Force immediate status update
            updateConnectionStatus();
            updateDeviceInfoForced();
            
            Log.d(TAG, "Auto-reconnection UI refresh completed");
        });
    }
    
    /**
     * Called by the bridge whenever a Config response is parsed and cached. The
     * Device-Information card reads from cached config; this push lets it re-render
     * after a post-reboot reconnect without the user toggling Disconnect/Connect.
     */
    public void onConfigUpdated() {
        uiHandler.post(() -> {
            updateConnectionStatus();
            updateDeviceInfoForced();
        });
    }

    /**
     * Called when device name is updated (e.g., when NodeInfo is received)
     * This refreshes the UI to show the updated device name
     */
    public void onDeviceNameUpdated() {
        Log.i(TAG, "Device name updated - refreshing UI");
        uiHandler.post(() -> {
            // Reload device name from preferences if we have a device address
            if (currentDeviceAddress != null) {
                String prefKey = "plugin.meshtastic.last_device_name_" + currentDeviceAddress;
                String updatedName = preferences.get(prefKey, "Meshtastic Device");
                Log.i(TAG, "Reloaded device name: " + prefKey + " = " + updatedName);
                currentDeviceName = updatedName;
            } else {
                Log.w(TAG, "Cannot reload device name - currentDeviceAddress is null");
                currentDeviceName = null;
            }
            
            // Force immediate status update to refresh the name display
            updateConnectionStatus();
            updateDeviceInfoForced();
            
            Log.i(TAG, "Device name UI refresh completed");
        });
    }
    
    /**
     * Get the intent filter for this receiver
     */
    public DocumentedIntentFilter getFilter() {
        DocumentedIntentFilter filter = new DocumentedIntentFilter();
        filter.addAction(SHOW_MESHTASTIC);
        return filter;
    }
    
    // ============================ Mesh tab (node roster) ============================

    private void setupMeshTab() {
        meshNodeList = templateView.findViewById(R.id.mesh_node_list);
        meshSummary = templateView.findViewById(R.id.mesh_summary);
        meshAdapter = new MeshNodeAdapter();
        if (meshNodeList != null) {
            meshNodeList.setAdapter(meshAdapter);
        }
    }

    /**
     * Rebuild the roster from the connected node's node DB. Nodes are grouped by hop distance —
     * so nodes beyond our direct radio horizon (learned via the mesh) show up under their own
     * "N hops away" section — and sorted within a group by signal quality.
     */
    private void refreshMeshTab() {
        if (meshAdapter == null) return;

        if (!isConnected()) {
            meshAdapter.setItems(new ArrayList<>());
            if (meshSummary != null) meshSummary.setText("Connect to see mesh nodes");
            return;
        }

        List<MeshProtos.NodeInfo> nodes = meshtasticBridge.getNodes();
        long myNum = -1L;
        MeshProtos.MyNodeInfo myInfo = meshtasticBridge.getMyNodeInfo();
        if (myInfo != null) myNum = myInfo.getNodeNum();

        // Bucket by hop distance: 0 = direct, 1.. = relayed, MAX_VALUE = unknown (no hops_away).
        java.util.TreeMap<Integer, List<MeshProtos.NodeInfo>> buckets = new java.util.TreeMap<>();
        int total = 0;
        int direct = 0;
        for (MeshProtos.NodeInfo n : nodes) {
            if (n.getNodeNum() == myNum) continue; // exclude our own node
            int bucket = n.getHasHops() ? n.getHopsAway() : Integer.MAX_VALUE; // unknown sorts last
            List<MeshProtos.NodeInfo> list = buckets.get(bucket);
            if (list == null) {
                list = new ArrayList<>();
                buckets.put(bucket, list);
            }
            list.add(n);
            total++;
            if (bucket == 0) direct++;
        }

        // Flatten into header + node rows, strongest SNR first within each hop group.
        List<Object> items = new ArrayList<>();
        for (Map.Entry<Integer, List<MeshProtos.NodeInfo>> e : buckets.entrySet()) {
            List<MeshProtos.NodeInfo> list = e.getValue();
            Collections.sort(list, (a, b) -> Float.compare(b.getSnr(), a.getSnr()));
            items.add(new MeshGroup(e.getKey(), list.size()));
            items.addAll(list);
        }

        meshAdapter.setItems(items);
        if (meshSummary != null) {
            int beyond = total - direct;
            meshSummary.setText(total + (total == 1 ? " node" : " nodes")
                    + " · " + direct + " direct · " + beyond + " beyond");
        }
    }

    /** Header marker for a hop-distance group in the roster. */
    private static class MeshGroup {
        final int hop; // 0 = direct, Integer.MAX_VALUE = unknown
        final int count;
        MeshGroup(int hop, int count) { this.hop = hop; this.count = count; }
    }

    private static String meshGroupLabel(int hop, int count) {
        String base;
        if (hop == 0) base = "DIRECT";
        else if (hop == Integer.MAX_VALUE) base = "UNKNOWN DISTANCE";
        else base = hop + (hop == 1 ? " HOP AWAY" : " HOPS AWAY");
        return base + "  ·  " + count;
    }

    /** Accent color per hop depth — greener when close, cooler/farther as hops increase. */
    private static int hopAccentColor(int hop) {
        switch (hop) {
            case 0:  return 0xFF43A047; // direct - green
            case 1:  return 0xFF26C6DA; // 1 hop - cyan
            case 2:  return 0xFF42A5F5; // 2 hops - blue
            case 3:  return 0xFF7E57C2; // 3 hops - purple
            case Integer.MAX_VALUE: return 0xFF666666; // unknown - gray
            default: return 0xFFAB47BC; // 4+ hops - magenta
        }
    }

    /** Map a Meshtastic SNR (dB) to 0..5 filled bars. */
    private static int snrLevel(float snr) {
        if (snr >= 8f)   return 5;
        if (snr >= 3f)   return 4;
        if (snr >= -2f)  return 3;
        if (snr >= -8f)  return 2;
        if (snr >= -15f) return 1;
        return 0;
    }

    private static int snrColor(int level) {
        switch (level) {
            case 5:
            case 4: return 0xFF43A047; // strong - green
            case 3: return 0xFFC0CA33; // ok - lime
            case 2: return 0xFFFB8C00; // weak - orange
            case 1: return 0xFFE53935; // poor - red
            default: return 0xFF777777; // none
        }
    }

    private static String formatLastHeard(long epochSecs) {
        if (epochSecs <= 0) return "";
        long delta = (System.currentTimeMillis() / 1000L) - epochSecs;
        if (delta < 0) delta = 0;
        if (delta < 60) return "now";
        if (delta < 3600) return (delta / 60) + "m";
        if (delta < 86400) return (delta / 3600) + "h";
        return (delta / 86400) + "d";
    }

    /**
     * Roster adapter: two row types (hop-group header + node row). Node rows render a segmented
     * SNR bar, per-node RSSI when we've heard it directly, battery and freshness.
     */
    private class MeshNodeAdapter extends BaseAdapter {
        private List<Object> items = new ArrayList<>();

        void setItems(List<Object> newItems) {
            items = newItems;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public boolean isEnabled(int position) { return false; } // non-clickable rows
        @Override public boolean areAllItemsEnabled() { return false; }

        @Override public int getItemViewType(int position) {
            return (items.get(position) instanceof MeshGroup) ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object item = items.get(position);
            LayoutInflater inflater = LayoutInflater.from(pluginContext);

            if (item instanceof MeshGroup) {
                MeshGroup g = (MeshGroup) item;
                TextView header = (TextView) convertView;
                if (header == null || !(header.getId() == R.id.group_header)) {
                    header = (TextView) inflater.inflate(R.layout.mesh_group_header, parent, false);
                }
                header.setText(meshGroupLabel(g.hop, g.count));
                header.setTextColor(hopAccentColor(g.hop));
                return header;
            }

            MeshProtos.NodeInfo node = (MeshProtos.NodeInfo) item;

            View view = convertView;
            if (view == null || view.findViewById(R.id.node_name) == null) {
                view = inflater.inflate(R.layout.mesh_node_item, parent, false);
            }

            // Name / long name
            String shortName = node.getShortName();
            String longName = node.getLongName();
            if (shortName == null || shortName.isEmpty()) {
                shortName = String.format("!%04x", node.getNodeNum() & 0xFFFF);
            }
            ((TextView) view.findViewById(R.id.node_name)).setText(shortName);
            TextView longView = view.findViewById(R.id.node_long);
            if (longName == null || longName.isEmpty() || longName.equals(shortName)) {
                longView.setText("");
                longView.setVisibility(View.GONE);
            } else {
                longView.setText(longName);
                longView.setVisibility(View.VISIBLE);
            }

            // Hop accent stripe
            int hop = node.getHasHops() ? node.getHopsAway() : Integer.MAX_VALUE;
            view.findViewById(R.id.hop_accent).setBackgroundColor(hopAccentColor(hop));

            // SNR bar
            float snr = node.getSnr();
            int level = snrLevel(snr);
            int color = snrColor(level);
            int[] segIds = { R.id.seg1, R.id.seg2, R.id.seg3, R.id.seg4, R.id.seg5 };
            for (int i = 0; i < segIds.length; i++) {
                view.findViewById(segIds[i]).setBackgroundColor(i < level ? color : 0xFF3A3A3A);
            }

            // SNR / RSSI text
            StringBuilder sig = new StringBuilder();
            sig.append(String.format("SNR %.1fdB", snr));
            Integer rssi = meshtasticBridge.getNodeRssi(node.getNodeNum());
            if (rssi != null) sig.append("  ·  ").append(rssi).append("dBm");
            ((TextView) view.findViewById(R.id.node_signal)).setText(sig.toString());

            // Battery
            TextView batteryView = view.findViewById(R.id.node_battery);
            int bl = node.getBatteryLevel();
            if (bl >= 0) {
                if (bl > 100) {
                    batteryView.setText("⚡");
                    batteryView.setTextColor(0xFF9CCC65);
                } else {
                    batteryView.setText(bl + "%");
                    batteryView.setTextColor(bl < 20 ? 0xFFE53935 : (bl < 50 ? 0xFFFB8C00 : 0xFF9CCC65));
                }
            } else {
                batteryView.setText("—");
                batteryView.setTextColor(0xFF888888);
            }

            // Freshness + stale dimming
            long lastHeard = node.getLastHeard();
            ((TextView) view.findViewById(R.id.node_lastheard)).setText(formatLastHeard(lastHeard));
            boolean stale = lastHeard > 0 && (System.currentTimeMillis() / 1000L - lastHeard) > 7200;
            view.findViewById(R.id.mesh_row_root).setAlpha(stale ? 0.45f : 1.0f);

            return view;
        }
    }

    /**
     * Device list adapter for the ListView
     */
    private class DeviceListAdapter extends BaseAdapter {
        private List<MeshtasticBleScanner.MeshtasticDevice> devices = new ArrayList<>();
        
        public void updateDevices(Map<String, MeshtasticBleScanner.MeshtasticDevice> deviceMap) {
            devices.clear();
            devices.addAll(deviceMap.values());
            
            // Sort by RSSI (strongest signal first) and Meshtastic devices first
            Collections.sort(devices, (d1, d2) -> {
                // Meshtastic devices first
                if (d1.isMeshtastic() && !d2.isMeshtastic()) return -1;
                if (!d1.isMeshtastic() && d2.isMeshtastic()) return 1;
                // Then by RSSI
                return Integer.compare(d2.getRssi(), d1.getRssi());
            });
            
            notifyDataSetChanged();
        }
        
        public void clearDevices() {
            devices.clear();
            notifyDataSetChanged();
        }
        
        @Override
        public int getCount() {
            return devices.size();
        }
        
        @Override
        public MeshtasticBleScanner.MeshtasticDevice getItem(int position) {
            return devices.get(position);
        }
        
        @Override
        public long getItemId(int position) {
            return position;
        }
        
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                LayoutInflater inflater = LayoutInflater.from(pluginContext);
                view = inflater.inflate(R.layout.device_list_item, parent, false);
            }
            
            MeshtasticBleScanner.MeshtasticDevice device = getItem(position);
            
            TextView nameText = view.findViewById(R.id.device_name);
            TextView addressText = view.findViewById(R.id.device_address);
            TextView rssiText = view.findViewById(R.id.device_rssi);
            ImageView icon = view.findViewById(R.id.device_icon);
            
            nameText.setText(device.getName());
            addressText.setText(device.getAddress());
            rssiText.setText("RSSI: " + device.getRssi() + " dBm");
            
            // Highlight Meshtastic devices
            if (device.isMeshtastic()) {
                nameText.setTextColor(0xFF00AA00); // Green for Meshtastic
                icon.setImageResource(R.drawable.ic_meshtastic);
            } else {
                nameText.setTextColor(0xFFFFFFFF); // White for others
                icon.setImageResource(R.drawable.ic_bluetooth);
            }
            
            return view;
        }
    }
}