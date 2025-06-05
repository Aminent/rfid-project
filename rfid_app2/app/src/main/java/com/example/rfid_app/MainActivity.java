package com.example.rfid_app;

import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import java.util.HashMap;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "RFID_APP";
    private RFIDWithUHFUART mReader;
    private TextView scannerStatus;
    private TextView lastTagInfo;
    private MaterialButton assignAssetButton;

    private boolean isRunning = false;
    private Thread inventoryThread;
    private boolean isEmulatorMode = false;
    private Map<String, TagInfo> tagMap = new HashMap<>();
    private SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private String lastScannedTag = null;
    private RFIDApiService apiService;

    private static class TagInfo {
        String epc;
        String rssi;
        int count;
        long lastSeen;

        TagInfo(String epc, String rssi) {
            this.epc = epc;
            this.rssi = rssi;
            this.count = 1;
            this.lastSeen = System.currentTimeMillis();
        }

        void update(String rssi) {
            this.rssi = rssi;
            this.count++;
            this.lastSeen = System.currentTimeMillis();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize API service
        apiService = RFIDApiService.getInstance();

        // Test server connection on startup
        testServerConnection();

        // Initialize views
        scannerStatus = findViewById(R.id.scannerStatus);
        lastTagInfo = findViewById(R.id.lastTagInfo);
        assignAssetButton = findViewById(R.id.assignAssetButton);

        // Setup click listeners
        assignAssetButton.setOnClickListener(v -> {
            if (lastScannedTag != null) {
                showAddAssetDialog(lastScannedTag);
            } else {
                showToast("No tag scanned yet");
            }
        });

        // Log device information
        String deviceInfo = String.format("Model: %s, Device: %s, Product: %s",
                Build.MODEL, Build.DEVICE, Build.PRODUCT);
        Log.d(TAG, "Device Info: " + deviceInfo);

        // Initialize RFID reader
        if (!isEmulator()) {
            initializeReader();
        } else {
            Log.d(TAG, "Running in emulator mode - using mock RFID reader");
            scannerStatus.setText(R.string.scanner_error);
            showToast("Running in emulator mode (Mock RFID)");
            isEmulatorMode = true;
        }
    }

    private void testServerConnection() {
        apiService.testConnection(new RFIDApiService.ApiCallback<String>() {
            @Override
            public void onSuccess(String result) {
                Log.d(TAG, "Server connection test: " + result);
                showToast("✅ Server connected");
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Server connection failed: " + error);
                showToast("❌ Server connection failed: " + error);
            }
        });
    }

    private void showAddAssetDialog(String tagId) {
        if (tagId == null) {
            showToast("No tag selected. Please scan a tag first.");
            return;
        }

        final String finalTagId = tagId;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add Asset for Tag: " + tagId);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("Asset Name *");
        layout.addView(nameInput);

        final EditText descriptionInput = new EditText(this);
        descriptionInput.setHint("Description");
        layout.addView(descriptionInput);

        final EditText categoryInput = new EditText(this);
        categoryInput.setHint("Category");
        layout.addView(categoryInput);

        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) {
                showToast("Asset name is required");
                return;
            }

            RFIDApiService.Asset asset = new RFIDApiService.Asset(
                name,                                    // name
                descriptionInput.getText().toString().trim(),  // description
                "Unassigned",                           // department
                "Unknown",                              // roomNumber
                "1",                                    // floor
                categoryInput.getText().toString().trim(), // category
                0.0,                                    // value
                null,                                   // location
                "Available",                            // status
                null                                    // owner
            );

            showToast("Saving asset...");
            apiService.assignAssetToTag(finalTagId, asset, new RFIDApiService.ApiCallback<String>() {
                @Override
                public void onSuccess(String result) {
                    showToast("✅ Asset saved successfully!");
                }

                @Override
                public void onError(String error) {
                    showToast("❌ Failed to save asset: " + error);
                }
            });
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.create().show();
    }

    private void updateDisplay() {
        runOnUiThread(() -> {
            if (tagMap.isEmpty()) {
                lastTagInfo.setText(R.string.no_tag_scanned);
                assignAssetButton.setEnabled(false);
            } else {
                // Display the most recently scanned tag
                TagInfo lastTag = null;
                long lastSeenTime = 0;
                for (TagInfo tag : tagMap.values()) {
                    if (tag.lastSeen > lastSeenTime) {
                        lastSeenTime = tag.lastSeen;
                        lastTag = tag;
                    }
                }

                if (lastTag != null) {
                    lastScannedTag = lastTag.epc;
                    String tagInfo = String.format(getString(R.string.tag_info),
                            lastTag.epc, lastTag.rssi);
                    lastTagInfo.setText(tagInfo);
                    assignAssetButton.setEnabled(true);
                }
            }
        });
    }

    private void startInventory() {
        if (mReader == null) {
            Log.e(TAG, "Reader is null");
            return;
        }

        isRunning = true;
        inventoryThread = new Thread(() -> {
            try {
                // Start inventory mode
                if (!mReader.startInventoryTag()) {
                    Log.e(TAG, "Failed to start inventory");
                    showToast("Failed to start inventory");
                    return;
                }

                while (isRunning) {
                    UHFTAGInfo tag = mReader.readTagFromBuffer();
                    if (tag != null) {
                        String epc = tag.getEPC();
                        String rssi = tag.getRssi();

                        if (epc != null) {
                            boolean isNewTag = false;
                            synchronized (tagMap) {
                                if (tagMap.containsKey(epc)) {
                                    tagMap.get(epc).update(rssi);
                                } else {
                                    tagMap.put(epc, new TagInfo(epc, rssi));
                                    isNewTag = true;
                                    // Only show toast for new tags
                                    showToast("📡 New tag detected: " + epc.substring(0, Math.min(8, epc.length())) + "...");
                                }
                            }

                            // Send tag reading to server
                            sendTagReadingToServer(epc, rssi, isNewTag);
                            updateDisplay();
                        }
                    }
                    Thread.sleep(100); // Increased delay to reduce server load
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in inventory thread", e);
                showToast("Error: " + e.getMessage());
            } finally {
                try {
                    mReader.stopInventory();
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping inventory", e);
                }
            }
        });
        inventoryThread.start();
    }

    private void sendTagReadingToServer(String epc, String rssi, boolean isNewTag) {
        // Only send to server if it's a new tag or every 10th reading to reduce server load
        TagInfo tagInfo = tagMap.get(epc);
        if (isNewTag || (tagInfo != null && tagInfo.count % 10 == 0)) {
            apiService.sendTagReading(epc, rssi, "Mobile Scanner", Build.MODEL,
                    new RFIDApiService.ApiCallback<String>() {
                        @Override
                        public void onSuccess(String result) {
                            Log.d(TAG, "Tag reading sent to server: " + epc);
                        }

                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "Failed to send tag reading: " + error);
                            // Don't show toast for every network error to avoid spam
                        }
                    });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isEmulatorMode && mReader != null) {
            try {
                if (!mReader.init(getApplicationContext())) {
                    showToast("Failed to reinitialize reader");
                    Log.e(TAG, "Failed to reinitialize reader in onResume");
                    scannerStatus.setText(getString(R.string.scanner_error, "Failed to initialize"));
                } else {
                    mReader.setPower(30);
                    tagMap.clear(); // Clear previous readings
                    scannerStatus.setText(R.string.scanner_ready);
                    startInventory();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in onResume", e);
                scannerStatus.setText(getString(R.string.scanner_error, e.getMessage()));
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isRunning = false;
        if (!isEmulatorMode && mReader != null) {
            try {
                mReader.stopInventory();
                mReader.free();
            } catch (Exception e) {
                Log.e(TAG, "Error in onPause", e);
            }
        }
    }

    @Override
    protected void onDestroy() {
        isRunning = false;
        if (inventoryThread != null) {
            try {
                inventoryThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Error joining inventory thread", e);
            }
        }
        if (!isEmulatorMode && mReader != null) {
            try {
                mReader.stopInventory();
                mReader.free();
            } catch (Exception e) {
                Log.e(TAG, "Error in onDestroy", e);
            }
        }
        super.onDestroy();
    }

    private void showToast(final String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk".equals(Build.PRODUCT)
                || Build.PRODUCT.contains("sdk_gphone")
                || Build.PRODUCT.contains("sdk_x86");
    }

    private boolean isDeviceSupported() {
        String model = Build.MODEL.toLowerCase();
        boolean isSupported = model.contains("c72e");
        Log.d(TAG, "Device support check - Model: " + model + ", Supported: " + isSupported);
        return isSupported;
    }

    private void initializeReader() {
        try {
            if (!isDeviceSupported()) {
                String message = "Device not supported. This app is designed for C72E. Current device: " + Build.MODEL;
                Log.e(TAG, message);
                showToast(message);
                scannerStatus.setText(getString(R.string.scanner_error, "Device not supported"));
                return;
            }

            // Get reader instance
            try {
                mReader = RFIDWithUHFUART.getInstance();
            } catch (Exception e) {
                Log.e(TAG, "Error getting reader instance", e);
                showToast("Error: Unable to get reader instance");
                scannerStatus.setText(getString(R.string.scanner_error, "Unable to get reader instance"));
                return;
            }

            if (mReader != null) {
                // Initialize reader
                try {
                    if (mReader.init(getApplicationContext())) {
                        Log.d(TAG, "Reader initialized successfully");

                        // Configure reader settings for C72E
                        try {
                            // Set power to 30 dBm (maximum for C72E)
                            mReader.setPower(30);

                            // Get current configuration
                            int power = mReader.getPower();
                            Log.d(TAG, "Power level: " + power);

                            // Start scanning
                            startInventory();
                            showToast("📡 Reader initialized (Power: " + power + "dBm)");
                            scannerStatus.setText(R.string.scanner_ready);
                        } catch (Exception e) {
                            Log.e(TAG, "Error configuring reader", e);
                            showToast("Error configuring reader: " + e.getMessage());
                            scannerStatus.setText(getString(R.string.scanner_error, "Configuration error"));
                        }
                    } else {
                        Log.e(TAG, "Failed to initialize reader");
                        showToast("Failed to initialize reader");
                        scannerStatus.setText(getString(R.string.scanner_error, "Initialization failed"));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error during initialization", e);
                    showToast("Error: " + e.getMessage());
                    scannerStatus.setText(getString(R.string.scanner_error, e.getMessage()));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in initializeReader", e);
            showToast("Error: " + e.getMessage());
            scannerStatus.setText(getString(R.string.scanner_error, e.getMessage()));
        }
    }
}