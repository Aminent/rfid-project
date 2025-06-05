package com.example.rfid_app;

import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import okhttp3.*;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.text.SimpleDateFormat;
import java.util.Date;

public class RFIDApiService {
    private static final String TAG = "RFIDApiService";
    private static final String BASE_URL = "http://192.168.1.6:3001/api"; // Change to your server IP
    private static RFIDApiService instance;

    private OkHttpClient client;
    private Gson gson;

    private RFIDApiService() {
        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();

        gson = new GsonBuilder()
                .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .create();
    }

    public static synchronized RFIDApiService getInstance() {
        if (instance == null) {
            instance = new RFIDApiService();
        }
        return instance;
    }

    // Data classes for API responses
    public static class ApiResponse<T> {
        public boolean success;
        public String message;
        public T data;
        public String error;
    }

    public static class TagReading {
        public String epc;
        public String rssi;
        public String timestamp;
        public String department;
        public String roomNumber;
        public String floor;
        public String location;
        public String deviceId;

        public TagReading(String epc, String rssi, String department, 
                         String roomNumber, String floor, String location, String deviceId) {
            this.epc = epc;
            this.rssi = rssi;
            this.department = department;
            this.roomNumber = roomNumber;
            this.floor = floor;
            this.location = location;
            this.deviceId = deviceId;
            this.timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .format(new Date());
        }
    }

    public static class Asset {
        public String name;
        public String description;
        public String department;    // Added field
        public String roomNumber;    // Added field
        public String floor;         // Added field
        public String category;
        public Double value;
        public String location;
        public String status;        // Added field for asset status
        public String owner;

        public Asset(String name, String description, String department, String roomNumber, 
                    String floor, String category, Double value, String location, 
                    String status, String owner) {
            this.name = name;
            this.description = description;
            this.department = department;
            this.roomNumber = roomNumber;
            this.floor = floor;
            this.category = category;
            this.value = value;
            this.location = location;
            this.status = status;
            this.owner = owner;
        }
    }

    public static class ReferenceData {
        public List<String> departments;
        public List<String> roomNumbers;
    }

    // Interface for callbacks
    public interface ApiCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    // Send tag reading to server
    public void sendTagReading(String epc, String rssi, String location, String deviceId, ApiCallback<String> callback) {
        // Create tag reading with default department/room values if not provided
        TagReading reading = new TagReading(
            epc,
            rssi,
            "Unassigned",  // default department
            "Unknown",     // default room number
            "0",          // default floor
            location,
            deviceId
        );
        String json = gson.toJson(reading);

        RequestBody body = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(BASE_URL + "/tags/reading")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to send tag reading", e);
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    Log.d(TAG, "Tag reading response: " + responseBody);

                    if (response.isSuccessful()) {
                        ApiResponse<?> apiResponse = gson.fromJson(responseBody, ApiResponse.class);
                        if (apiResponse.success) {
                            callback.onSuccess("Tag reading sent successfully");
                        } else {
                            callback.onError(apiResponse.message != null ? apiResponse.message : "Unknown error");
                        }
                    } else {
                        callback.onError("Server error: " + response.code());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing response", e);
                    callback.onError("Error parsing response: " + e.getMessage());
                }
            }
        });
    }

    // Overloaded method to send tag reading with all parameters
    public void sendTagReading(
        String epc, 
        String rssi, 
        String department,
        String roomNumber,
        String floor,
        String location,
        String deviceId,
        ApiCallback<String> callback) {
        
        TagReading reading = new TagReading(epc, rssi, department, roomNumber, floor, location, deviceId);
        String json = gson.toJson(reading);

        RequestBody body = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(BASE_URL + "/tags/reading")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to send tag reading", e);
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    Log.d(TAG, "Tag reading response: " + responseBody);

                    if (response.isSuccessful()) {
                        ApiResponse<?> apiResponse = gson.fromJson(responseBody, ApiResponse.class);
                        if (apiResponse.success) {
                            callback.onSuccess("Tag reading sent successfully");
                        } else {
                            callback.onError(apiResponse.message != null ? apiResponse.message : "Unknown error");
                        }
                    } else {
                        callback.onError("Server error: " + response.code());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing response", e);
                    callback.onError("Error parsing response: " + e.getMessage());
                }
            }
        });
    }

    // Assign asset to tag
    public void assignAssetToTag(String epc, Asset asset, ApiCallback<String> callback) {
        String json = gson.toJson(asset);

        RequestBody body = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(BASE_URL + "/tags/" + epc + "/asset")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to assign asset", e);
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    Log.d(TAG, "Assign asset response: " + responseBody);

                    if (response.isSuccessful()) {
                        ApiResponse<?> apiResponse = gson.fromJson(responseBody, ApiResponse.class);
                        if (apiResponse.success) {
                            callback.onSuccess("Asset assigned successfully");
                        } else {
                            callback.onError(apiResponse.message != null ? apiResponse.message : "Unknown error");
                        }
                    } else {
                        callback.onError("Server error: " + response.code());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing response", e);
                    callback.onError("Error parsing response: " + e.getMessage());
                }
            }
        });
    }

    // Get all tags
    public void getAllTags(ApiCallback<String> callback) {
        Request request = new Request.Builder()
                .url(BASE_URL + "/tags")
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to get tags", e);
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    Log.d(TAG, "Get tags response: " + responseBody);

                    if (response.isSuccessful()) {
                        callback.onSuccess(responseBody);
                    } else {
                        callback.onError("Server error: " + response.code());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing response", e);
                    callback.onError("Error parsing response: " + e.getMessage());
                }
            }
        });
    }

    // Test server connection
    public void testConnection(ApiCallback<String> callback) {
        Request request = new Request.Builder()
                .url(BASE_URL.replace("/api", "/health"))
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Connection test failed", e);
                callback.onError("Cannot connect to server: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    Log.d(TAG, "Health check response: " + responseBody);

                    if (response.isSuccessful()) {
                        callback.onSuccess("Server connection successful");
                    } else {
                        callback.onError("Server returned error: " + response.code());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing health response", e);
                    callback.onError("Error reading server response");
                }
            }
        });
    }

    // Fetch reference data (departments, room numbers, etc.)
    public void fetchReferences(ApiCallback<ReferenceData> callback) {
        Request request = new Request.Builder()
                .url(BASE_URL + "/references")
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to fetch references", e);
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    if (response.isSuccessful()) {
                        ApiResponse<ReferenceData> apiResponse = gson.fromJson(responseBody, 
                            new TypeToken<ApiResponse<ReferenceData>>(){}.getType());
                        if (apiResponse.success) {
                            callback.onSuccess(apiResponse.data);
                        } else {
                            callback.onError(apiResponse.message != null ? apiResponse.message : "Unknown error");
                        }
                    } else {
                        callback.onError("Server error: " + response.code());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing response", e);
                    callback.onError("Error parsing response: " + e.getMessage());
                }
            }
        });
    }
}