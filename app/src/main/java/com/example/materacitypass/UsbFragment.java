package com.example.materacitypass;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class UsbFragment extends Fragment {

    private static final String ACTION_USB_PERMISSION = "com.example.materacitypass.USB_PERMISSION";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private static final int CH340_VENDOR_ID = 0x1a86;
    private static final int CH340_PRODUCT_ID = 0x7523;

    // Configurazione riconnessione automatica
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MS = 2000;
    private static final long HEALTH_CHECK_INTERVAL_MS = 5000;

    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private UsbDeviceConnection connection;
    private UsbSerialDevice serialPort;

    private boolean isConnected = false;
    private boolean usbPermissionRequested = false;
    private boolean isReconnecting = false;
    private int reconnectAttempts = 0;

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable healthCheckRunnable;
    private Runnable reconnectRunnable;

    // UI Components
    private TextView tvStatus, tvDeviceInfo, tvSerialMonitor;
    private Button btnConnect, btnDisconnect, btnTest, btnSaveWifi;
    private EditText etWifiSsid, etWifiPassword;
    private EditText etActivityName, etActivityId;
    private Button btnSaveActivity;
    private View layoutConnected;
    private CheckBox cbMuseo;
    private NestedScrollView scrollViewSerial;

    private StringBuilder serialBuffer = new StringBuilder();
    private static final int MAX_SERIAL_BUFFER = 5000;
    private boolean waitingForWifiResponse = false;
    private long lastDataReceivedTime = 0;





    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (context == null || intent == null) return;

            String action = intent.getAction();
            if (action == null) return;

            try {
                if (ACTION_USB_PERMISSION.equals(action)) {
                    handleUsbPermissionResult(intent);
                } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    handleDeviceAttached(intent);
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    handleDeviceDetached(intent);
                }
            } catch (Exception e) {
                logError("Errore in usbReceiver: " + e.getMessage());
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_usb, container, false);

        initializeViews(view);
        initializeUsb();
        setupListeners();

        layoutConnected.setVisibility(View.GONE);
        tvStatus.setText("🔌 Pronto per connettersi");

        return view;
    }

    private void initializeViews(View view) {
        tvStatus = view.findViewById(R.id.tv_status);
        tvDeviceInfo = view.findViewById(R.id.tv_device_info);
        tvSerialMonitor = view.findViewById(R.id.tv_serial_monitor);
        btnConnect = view.findViewById(R.id.btn_connect);
        btnDisconnect = view.findViewById(R.id.btn_disconnect);
        btnTest = view.findViewById(R.id.btn_test);
        cbMuseo = view.findViewById(R.id.cb_museo);
        btnSaveWifi = view.findViewById(R.id.btn_save_wifi);
        etWifiSsid = view.findViewById(R.id.et_wifi_ssid);
        etWifiPassword = view.findViewById(R.id.et_wifi_password);
        layoutConnected = view.findViewById(R.id.layout_connected);
        scrollViewSerial = view.findViewById(R.id.scroll_serial);
        etActivityName = view.findViewById(R.id.et_activity_name);
        etActivityId = view.findViewById(R.id.et_activity_id);
        btnSaveActivity = view.findViewById(R.id.btn_save_activity);

    }

    private void initializeUsb() {
        try {
            Context context = getContext();
            if (context == null) {
                logError("Context is null");
                return;
            }

            usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);

            if (usbManager == null) {
                tvStatus.setText("❌ USB non disponibile su questo dispositivo");
                btnConnect.setEnabled(false);
                return;
            }

            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_USB_PERMISSION);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            context.registerReceiver(usbReceiver, filter);

            autoConnectIfDeviceAvailable();

        } catch (Exception e) {
            logError("Errore inizializzazione USB: " + e.getMessage());
            tvStatus.setText("❌ Errore inizializzazione USB");
        }
    }

    private void setupListeners() {
        btnConnect.setOnClickListener(v -> {
            reconnectAttempts = 0;
            scanForCH340Device();
        });
        btnDisconnect.setOnClickListener(v -> {
            stopReconnectionAttempts();
            disconnect();
        });
        btnTest.setOnClickListener(v -> sendTestCommand());
        btnSaveWifi.setOnClickListener(v -> saveWifiSettings());
        btnSaveActivity.setOnClickListener(v -> saveActivitySettings());
    }

    private void handleUsbPermissionResult(Intent intent) {
        synchronized (this) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                if (device != null) {
                    connectToDevice(device);
                }
            } else {
                updateStatus("❌ Permesso USB negato");
                btnConnect.setEnabled(true);
                usbPermissionRequested = false;
            }
        }
    }

    private void handleDeviceAttached(Intent intent) {
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device != null && isCH340Device(device)) {
            updateStatus("✓ Dispositivo rilevato");
            if (!isConnected && !isReconnecting) {
                handler.postDelayed(() -> requestUsbPermission(device), 500);
            }
        }
    }

    private void handleDeviceDetached(Intent intent) {
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device != null && device.equals(usbDevice)) {
            logError("Dispositivo disconnesso fisicamente");
            cleanupConnection();
            updateStatus("🔌 Dispositivo USB disconnesso");

            // Tentativo di riconnessione automatica
            if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                scheduleReconnection();
            }
        }
    }

    private void autoConnectIfDeviceAvailable() {
        try {
            if (usbManager == null) return;

            // Assicurati che l'UI sia nello stato disconnesso all'avvio
            updateUIDisconnected();

            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            if (deviceList == null || deviceList.isEmpty()) {
                updateStatus("🔌 Pronto per connettersi - Collega dispositivo via USB");
                btnConnect.setEnabled(true);
                return;
            }

            for (UsbDevice device : deviceList.values()) {
                if (device != null && isCH340Device(device)) {
                    usbDevice = device;
                    if (usbManager.hasPermission(device)) {
                        connectToDevice(device);
                    } else {
                        updateStatus("⚠️ Richiesta permessi USB necessari");
                        btnConnect.setEnabled(true);
                    }
                    return;
                }
            }

            updateStatus("🔌 Nessun dispositivo trovato");
            btnConnect.setEnabled(true);

        } catch (Exception e) {
            logError("Errore auto-connect: " + e.getMessage());
            btnConnect.setEnabled(true);
        }
    }

    private void scanForCH340Device() {
        try {
            updateStatus("🔍 Ricerca dispositivo...");
            btnConnect.setEnabled(false);

            if (usbManager == null) {
                updateStatus("❌ USB Manager non disponibile");
                btnConnect.setEnabled(true);
                return;
            }

            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();

            if (deviceList == null || deviceList.isEmpty()) {
                updateStatus("❌ Nessun dispositivo USB trovato\nCollega il dispositivo via cavo USB OTG");
                btnConnect.setEnabled(true);
                return;
            }

            boolean deviceFound = false;
            for (Map.Entry<String, UsbDevice> entry : deviceList.entrySet()) {
                UsbDevice device = entry.getValue();
                if (device == null) continue;

                android.util.Log.d("USB_SCAN", "Device: " + device.getDeviceName() +
                        " VID: " + Integer.toHexString(device.getVendorId()) +
                        " PID: " + Integer.toHexString(device.getProductId()));

                if (isCH340Device(device)) {
                    android.util.Log.d("USB_SCAN", "✓ Dispositivo trovato!");
                    deviceFound = true;
                    usbDevice = device;
                    requestUsbPermission(device);
                    break;
                }
            }

            if (!deviceFound) {
                updateStatus("❌ Dispositivo non trovato\nVerifica il collegamento USB OTG");
                btnConnect.setEnabled(true);
            }

        } catch (Exception e) {
            logError("Errore scan dispositivi: " + e.getMessage());
            updateStatus("❌ Errore durante la ricerca");
            btnConnect.setEnabled(true);
        }
    }

    private boolean isCH340Device(UsbDevice device) {
        if (device == null) return false;
        return device.getVendorId() == CH340_VENDOR_ID &&
                device.getProductId() == CH340_PRODUCT_ID;
    }

    private void requestUsbPermission(UsbDevice device) {
        if (device == null || usbManager == null) return;

        if (usbPermissionRequested) {
            android.util.Log.d("USB_PERMISSION", "Permesso USB già richiesto, salto.");
            return;
        }

        try {
            usbPermissionRequested = true;
            Context context = getContext();
            if (context == null) return;

            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    new Intent(ACTION_USB_PERMISSION),
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
                            PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
            );

            if (!usbManager.hasPermission(device)) {
                updateStatus("⚠️ Richiesta permessi USB...");
                usbManager.requestPermission(device, permissionIntent);
            } else {
                connectToDevice(device);
            }
        } catch (Exception e) {
            logError("Errore richiesta permessi: " + e.getMessage());
            usbPermissionRequested = false;
            btnConnect.setEnabled(true);
        }
    }

    private void connectToDevice(UsbDevice device) {
        if (device == null || usbManager == null) {
            updateStatus("❌ Dispositivo o manager non valido");
            btnConnect.setEnabled(true);
            updateUIDisconnected();
            return;
        }

        try {
            updateStatus("🔄 Connessione in corso...");

            // Pulisci eventuali connessioni precedenti
            cleanupConnection();

            // Assicurati che l'UI sia pulita prima di tentare la connessione
            requireActivity().runOnUiThread(() -> {
                layoutConnected.setVisibility(View.GONE);
                btnConnect.setVisibility(View.VISIBLE);
            });

            connection = usbManager.openDevice(device);
            if (connection == null) {
                updateStatus("❌ Impossibile aprire dispositivo USB");
                updateUIDisconnected();
                btnConnect.setEnabled(true);
                usbPermissionRequested = false;
                scheduleReconnection();
                return;
            }

            serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection);
            if (serialPort == null) {
                updateStatus("❌ Driver CH340 non supportato");
                closeConnection();
                updateUIDisconnected();
                btnConnect.setEnabled(true);
                usbPermissionRequested = false;
                return;
            }

            if (!serialPort.open()) {
                updateStatus("❌ Impossibile aprire porta seriale");
                closeConnection();
                updateUIDisconnected();
                btnConnect.setEnabled(true);
                usbPermissionRequested = false;
                scheduleReconnection();
                return;
            }

            // Configurazione porta seriale
            serialPort.setBaudRate(115200);
            serialPort.setDataBits(UsbSerialInterface.DATA_BITS_8);
            serialPort.setStopBits(UsbSerialInterface.STOP_BITS_1);
            serialPort.setParity(UsbSerialInterface.PARITY_NONE);
            serialPort.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);

            serialPort.read(mCallback);

            isConnected = true;
            isReconnecting = false;
            reconnectAttempts = 0;
            lastDataReceivedTime = System.currentTimeMillis();

            android.util.Log.d("USB_SERIAL", "✅ Connessione stabilita");

            requireActivity().runOnUiThread(() -> {
                updateStatus("✅ Connesso!");
                tvDeviceInfo.setText("📡 Dispositivo: ESP32 CH340\n" +
                        "📍 Device: " + device.getDeviceName() + "\n" +
                        "🔧 Baud Rate: 115200");

                // Pulisci e prepara l'UI per la connessione
                serialBuffer.setLength(0);
                tvSerialMonitor.setText("");
                etWifiSsid.setText("");
                etWifiPassword.setText("");

                // Mostra l'UI connessa
                updateUIConnected();

                updateSerialMonitor("=== Monitor Seriale Avviato ===");

                handler.postDelayed(() -> {
                    if (isConnected && serialPort != null) {
                        sendData("GET_PARAMETERS");
                        updateSerialMonitor(">> GET_PARAMETERS");
                        waitingForWifiResponse = true;
                    }
                }, 250);
            });

            // Avvia controllo salute connessione
            startHealthCheck();

        } catch (Exception e) {
            logError("Errore connessione: " + e.getMessage());
            updateStatus("❌ Errore durante la connessione");
            cleanupConnection();
            updateUIDisconnected();
            btnConnect.setEnabled(true);
            scheduleReconnection();
        }
    }

    private final UsbSerialInterface.UsbReadCallback mCallback = data -> {
        if (data == null || data.length == 0) return;

        try {
            lastDataReceivedTime = System.currentTimeMillis();
            String receivedText = new String(data, StandardCharsets.UTF_8).trim();
            android.util.Log.d("USB_SERIAL", "Ricevuto: " + receivedText);

            if (getActivity() == null) return;

            requireActivity().runOnUiThread(() -> {
                try {
                    if (waitingForWifiResponse && receivedText.startsWith("DEVICE_PARAMETERS:")) {
                        String credentials = receivedText.substring("DEVICE_PARAMETERS:".length()).trim();
                        String[] parts = credentials.split(" ");

                        if (parts.length >= 5) {
                            // Nuovo formato: WIFI_PARAMETERS: ssid password nomeattività idattività museo
                            etWifiSsid.setText(parts[0]);
                            etWifiPassword.setText(parts[1]);
                            etActivityName.setText(parts[2]);
                            etActivityId.setText(parts[3]);

                            // Imposta checkbox museo
                            cbMuseo.setChecked(parts[4].equals("1"));

                            waitingForWifiResponse = false;
                            updateSerialMonitor("✓ WiFi e Attività ottenuti");
                        } else if (parts.length >= 4) {
                            // Formato senza museo (retrocompatibilità)
                            etWifiSsid.setText(parts[0]);
                            etWifiPassword.setText(parts[1]);
                            etActivityName.setText(parts[2]);
                            etActivityId.setText(parts[3]);
                            cbMuseo.setChecked(false);
                            waitingForWifiResponse = false;
                            updateSerialMonitor("✓ WiFi e Attività ottenuti");
                        } else if (parts.length >= 2) {
                            // Vecchio formato: solo SSID e password
                            etWifiSsid.setText(parts[0]);
                            etWifiPassword.setText(parts[1]);
                            waitingForWifiResponse = false;
                            updateSerialMonitor("✓ Credenziali WiFi ottenute");
                        } else {
                            updateSerialMonitor(receivedText);
                        }
                    } else {
                        updateSerialMonitor(receivedText);
                    }
                } catch (Exception e) {
                    logError("Errore processing  " + e.getMessage());
                }
            });
        } catch (Exception e) {
            logError("Errore callback: " + e.getMessage());
        }
    };

    private void startHealthCheck() {
        stopHealthCheck();

        healthCheckRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isConnected) return;

                    // Verifica se la connessione è ancora valida
                    if (serialPort == null || connection == null) {
                        logError("Connessione persa - tentativo riconnessione");
                        handleConnectionLoss();
                        return;
                    }

                    // Verifica timeout dati (nessun dato ricevuto da troppo tempo)
                    long timeSinceLastData = System.currentTimeMillis() - lastDataReceivedTime;
                    if (timeSinceLastData > 30000) { // 30 secondi
                        android.util.Log.w("USB_HEALTH", "Nessun dato ricevuto da " +
                                timeSinceLastData / 1000 + " secondi");
                    }

                    // Ricontrolla tra HEALTH_CHECK_INTERVAL_MS
                    handler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS);

                } catch (Exception e) {
                    logError("Errore health check: " + e.getMessage());
                }
            }
        };

        handler.postDelayed(healthCheckRunnable, HEALTH_CHECK_INTERVAL_MS);
    }

    private void stopHealthCheck() {
        if (healthCheckRunnable != null) {
            handler.removeCallbacks(healthCheckRunnable);
            healthCheckRunnable = null;
        }
    }

    private void handleConnectionLoss() {
        cleanupConnection();

        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            scheduleReconnection();
        } else {
            requireActivity().runOnUiThread(() -> {
                updateStatus("❌ Connessione persa - riconnessione manuale necessaria");
                updateUIDisconnected();
            });
        }
    }

    private void scheduleReconnection() {
        if (isReconnecting) return;

        isReconnecting = true;
        reconnectAttempts++;

        stopReconnectionAttempts(); // Pulisci eventuali tentativi precedenti

        reconnectRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isReconnecting) return;

                    updateStatus("🔄 Tentativo riconnessione " + reconnectAttempts +
                            "/" + MAX_RECONNECT_ATTEMPTS + "...");

                    if (usbDevice != null && usbManager != null &&
                            usbManager.hasPermission(usbDevice)) {
                        connectToDevice(usbDevice);
                    } else {
                        scanForCH340Device();
                    }
                } catch (Exception e) {
                    logError("Errore riconnessione: " + e.getMessage());
                    isReconnecting = false;
                }
            }
        };

        handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
    }

    private void saveActivitySettings() {
        if (!isConnected) {
            showToast("❌ Non connesso");
            return;
        }

        String activityName = etActivityName.getText().toString().trim();
        String activityId = etActivityId.getText().toString().trim();

        int museo = cbMuseo.isChecked() ? 1 : 0;

        if (activityName.isEmpty() || activityId.isEmpty()) {
            showToast("❌ Inserisci nome e ID attività");
            return;
        }

        String command = "SAVE_ACTIVITY: " + activityName + " " + activityId + " " + museo;
        sendData(command);
        updateSerialMonitor(">> " + command);
        showToast("✅ Configurazione Attività inviata");
    }

    private void stopReconnectionAttempts() {
        isReconnecting = false;
        if (reconnectRunnable != null) {
            handler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }

    private void updateUIConnected() {
        btnConnect.setVisibility(View.GONE);
        layoutConnected.setVisibility(View.VISIBLE);
        btnDisconnect.setEnabled(true);
        btnConnect.setEnabled(false);
    }

    private void updateUIDisconnected() {
        btnConnect.setVisibility(View.VISIBLE);
        layoutConnected.setVisibility(View.GONE);
        btnDisconnect.setEnabled(false);
        btnConnect.setEnabled(true);
        tvDeviceInfo.setText("");
        serialBuffer.setLength(0);
        tvSerialMonitor.setText("");
    }

    private void updateSerialMonitor(String message) {
        try {
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date());
            String line = "[" + timestamp + "] " + message;
            if (!message.endsWith("\n")) {
                line += "\n";
            }

            serialBuffer.append(line);

            if (serialBuffer.length() > MAX_SERIAL_BUFFER) {
                serialBuffer.delete(0, serialBuffer.length() - MAX_SERIAL_BUFFER);
            }

            if (tvSerialMonitor != null) {
                tvSerialMonitor.setText(serialBuffer.toString());
            }
            if (scrollViewSerial != null) {
                scrollViewSerial.post(() -> scrollViewSerial.fullScroll(View.FOCUS_DOWN));
            }
        } catch (Exception e) {
            logError("Errore update monitor: " + e.getMessage());
        }
    }

    private void saveWifiSettings() {
        if (!isConnected) {
            showToast("❌ Non connesso");
            return;
        }

        String ssid = etWifiSsid.getText().toString().trim();
        String password = etWifiPassword.getText().toString().trim();

        if (ssid.isEmpty()) {
            showToast("❌ Inserisci SSID");
            return;
        }

        String command = "SAVE_WIFI: " + ssid + " " + password;
        sendData(command);
        updateSerialMonitor(">> " + command);
        showToast("✅ Configurazione WiFi inviata");
    }

    private void sendTestCommand() {
        if (!isConnected) {
            showToast("❌ Non connesso");
            return;
        }

        sendData("TEST");
        updateSerialMonitor(">> TEST");
        showToast("📤 Comando TEST inviato");
    }

    private void sendData(String data) {
        if (!isConnected || serialPort == null) {
            showToast("❌ Non connesso");
            return;
        }

        try {
            String dataWithNewline = data + "\n";
            byte[] bytes = dataWithNewline.getBytes(StandardCharsets.UTF_8);
            serialPort.write(bytes);
            android.util.Log.d("USB_SERIAL", "Inviato: " + data);
        } catch (Exception e) {
            logError("Errore invio dati: " + e.getMessage());
            handleConnectionLoss();
        }
    }

    private void disconnect() {
        cleanupConnection();

        if (getActivity() != null) {
            requireActivity().runOnUiThread(() -> {
                updateStatus("🔌 Disconnesso");
                updateUIDisconnected();
            });
        }
    }

    private void cleanupConnection() {
        stopHealthCheck();
        stopReconnectionAttempts();
        closeConnection();

        isConnected = false;
        usbPermissionRequested = false;
    }

    private void closeConnection() {
        try {
            if (serialPort != null) {
                serialPort.close();
                serialPort = null;
            }
        } catch (Exception e) {
            logError("Errore chiusura serial port: " + e.getMessage());
        }

        try {
            if (connection != null) {
                connection.close();
                connection = null;
            }
        } catch (Exception e) {
            logError("Errore chiusura connessione: " + e.getMessage());
        }
    }

    private void updateStatus(String status) {
        if (getActivity() != null && tvStatus != null) {
            requireActivity().runOnUiThread(() -> tvStatus.setText(status));
        }
    }

    private void showToast(String message) {
        if (getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    private void logError(String message) {
        android.util.Log.e("USB_SERIAL", message);
        updateSerialMonitor("⚠️ " + message);
    }

    @Override
    public void onPause() {
        super.onPause();
        // Mantieni la connessione attiva
    }

    @Override
    public void onResume() {
        super.onResume();

        // Verifica lo stato reale della connessione quando il fragment torna visibile
        if (isConnected && (serialPort == null || connection == null)) {
            // Lo stato dice connesso ma la connessione è persa
            logError("Stato inconsistente rilevato - cleanup necessario");
            isConnected = false;
            cleanupConnection();
            updateUIDisconnected();
            updateStatus("🔌 Disconnesso - Riapri connessione");
        } else if (isConnected) {
            // Connessione valida - riprendi health check
            startHealthCheck();
            updateUIConnected();
        } else {
            // Non connesso - assicurati che l'UI sia corretta
            updateUIDisconnected();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        stopHealthCheck();
        stopReconnectionAttempts();
        disconnect();

        try {
            if (getContext() != null) {
                getContext().unregisterReceiver(usbReceiver);
            }
        } catch (Exception e) {
            android.util.Log.e("USB_SERIAL", "Errore unregister receiver: " + e.getMessage());
        }
    }
}