package de.kai_morich.simple_bluetooth_le_terminal;

import static android.app.PendingIntent.getActivity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.widget.ArrayAdapter;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    private PreviewView previewView;
    private ImageView imageViewOverlay;
    private YoloHelper yoloHelper;
    private static final int REQUEST_CODE_PERMISSIONS = 1001;
    private static final String TAG = "BLE_Scan";

    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothAdapter bluetoothAdapter;
    private final String TARGET_DEVICE_NAME = "hmsoft";
    private BluetoothGatt bluetoothGatt;

    private static final UUID HM10_SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");
    private static final UUID HM10_CHARACTERISTIC_UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB");
    private WifiConnectionReceiver wifiReceiver;

    int speed = 0;


    @RequiresApi(api = Build.VERSION_CODES.S)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportFragmentManager().addOnBackStackChangedListener(this);
        if (savedInstanceState == null)
            getSupportFragmentManager().beginTransaction().add(R.id.fragment, new DevicesFragment(), "devices").commit();
        else
            onBackStackChanged();

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        previewView = findViewById(R.id.previewView);
        imageViewOverlay = findViewById(R.id.imageViewOverlay);

        try {
            yoloHelper = new YoloHelper(this, "yolov5_640.tflite", "coco.txt");
        } catch (IOException e) {
            e.printStackTrace();
        }
        Utility.appContext = getApplicationContext();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
            wifiReceiver = new WifiConnectionReceiver();
            registerReceiver(wifiReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
            if(Objects.equals(getCurrentSsid(this), "ESP32CAM_HOTSPOT"))
                getTrafficStatus();
            else
                setupWifiSuggestion();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_WIFI_STATE}, 101);
            setupWifiSuggestion();
        }
    }

    private void setupWifiSuggestion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                    .setSsid("ESP32CAM_HOTSPOT")
                    // .setWpa2Passphrase("your_password") // 필요 시 비밀번호 설정
                    .build();

            List<WifiNetworkSuggestion> suggestions = Collections.singletonList(suggestion);

            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int status = wifiManager.addNetworkSuggestions(suggestions);

            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                Log.d("MainActivity", "Wi-Fi 네트워크 제안 성공");
            } else {
                Log.d("MainActivity", "Wi-Fi 제안 실패: " + status);
            }
        }
    }

    public static String getCurrentSsid(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager != null) {
                for (Network network : connectivityManager.getAllNetworks()) {
                    NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                    if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                        String ssid = wifiInfo.getSSID();
                        return ssid != null ? ssid.replace("\"", "") : null;
                    }
                }
            }
            return null;
        } else {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            String ssid = wifiInfo.getSSID();
            return ssid != null ? ssid.replace("\"", "") : null;
        }
    }
    @Override
    public void onBackStackChanged() {
        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount()>0);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // 1. 미리보기 설정
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // 2. 분석기 (YOLO 입력용 이미지 프레임)
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 640)) // YOLO 모델 입력 사이즈
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor(), image -> {
                    Bitmap bitmap = toBitmap(image); // ImageProxy → Bitmap
                    Bitmap result = yoloHelper.detect(bitmap); // YOLO 실행
                    runOnUiThread(() -> imageViewOverlay.setImageBitmap(result)); // 결과 표시
                    image.close(); // 프레임 릴리스
                });

                // 3. 후면 카메라 사용
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                // 4. 바인딩
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageAnalysis
                );

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));

        if (!hasPermissions()) {
            requestPermissions();
        } else {
            startBleScan();
        }
    }

    private Bitmap toBitmap(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 100, out);
        byte[] jpegBytes = out.toByteArray();
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);

        // 회전 각도 얻기
        int rotationDegrees = image.getImageInfo().getRotationDegrees();

        List<String> detected = yoloHelper.getLastDetectedClasses();  // 이 메서드는 직접 만들어야 함

        runOnUiThread(() -> {
            getTrafficStatus();
            Log.d("traffic", String.valueOf(traffic));
            if (!detected.isEmpty()) {
                if(speed >10)
                {
                    vibrate();
                    Utility.speak("차량 접근 중입니다.");
                }
                else Utility.speak("차량이 정지했습니다. 조심히 건너세요.");
            }
            else if(Objects.equals(getCurrentSsid(Utility.appContext), "ESP32CAM_HOTSPOT")) {
                if(traffic) {
                    vibrate();
                    Utility.speak("초록불입니다. 조심히 건너세요.");
                }
                else
                    Utility.speak("빨간불입니다.");
            }
        });
        return rotateBitmap(bitmap, rotationDegrees);
    }

    private Bitmap rotateBitmap(Bitmap bitmap, int rotationDegrees) {
        if (rotationDegrees == 0) return bitmap;
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.postRotate(rotationDegrees);
        Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        bitmap.recycle();
        return rotatedBitmap;
    }

    public void vibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(500); // Deprecated, but works on older devices
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void startBleScan() {
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner != null) {
            bluetoothLeScanner.startScan(scanCallback);
            Log.d(TAG, "BLE scan started");
        } else {
            Log.e(TAG, "BluetoothLeScanner is null");
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @RequiresPermission(allOf = {Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN})
        @Override
        public void onScanResult(int callbackType, @NonNull ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String deviceName = device.getName();

            if (deviceName != null) {
                Log.d(TAG, "Device found: " + deviceName + " (" + device.getAddress() + ")");
                if (deviceName.equalsIgnoreCase(TARGET_DEVICE_NAME)) {
                    Log.d(TAG, "Target device found! Connecting to " + deviceName);
                    stopBleScan();  // 스캔 중지
                    connectToDevice(device);
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "Scan failed with error code: " + errorCode);
        }
    };

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private void connectToDevice(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {  // Android 12 이상
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT},
                    REQUEST_CODE_PERMISSIONS);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_CODE_PERMISSIONS);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Connected to GATT server.");
                    // 서비스 탐색 시작
                    bluetoothGatt.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "Disconnected from GATT server.");
                }
            } else {
                Log.e(TAG, "Connection state change error: " + status);
                gatt.close();
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered:");
                BluetoothGattService hm10Service = gatt.getService(HM10_SERVICE_UUID);
                if (hm10Service != null) {
                    BluetoothGattCharacteristic characteristic = hm10Service.getCharacteristic(HM10_CHARACTERISTIC_UUID);
                    if (characteristic != null) {
                        // Notify 활성화
                        boolean notifySet = gatt.setCharacteristicNotification(characteristic, true);
                        Log.d(TAG, "Notify set result: " + notifySet);

                        // CCCD (Client Characteristic Configuration Descriptor) 찾아서 Notify enable 플래그 설정
                        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                        if (descriptor != null) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            boolean writeDesc = gatt.writeDescriptor(descriptor);
                            Log.d(TAG, "Write descriptor result: " + writeDesc);
                        } else {
                            Log.e(TAG, "CCCD descriptor not found!");
                        }
                    } else {
                        Log.e(TAG, "HM-10 characteristic not found!");
                    }
                } else {
                    Log.e(TAG, "HM-10 service not found!");
                }
            } else {
                Log.e(TAG, "Service discovery failed with status: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);

            if (HM10_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();
                if (data != null && data.length > 0) {
                    String received = new String(data);  // 바이트 배열 → 문자열 변환
                    speed = Integer.parseInt(received.replaceAll("[^0-9]",""));
                }
            }
        }

        // 필요하면 characteristic 읽기/쓰기 콜백도 구현 가능
    };

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private void stopBleScan() {
        if (bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(scanCallback);
            Log.d(TAG, "BLE scan stopped");
        }
    }

    private static final String URL = "http://192.168.4.1/json";
    private boolean traffic = false;
    private void getTrafficStatus() {
        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url(URL)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                e.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Unexpected code " + response);
                    return;
                }

                String jsonData = response.body().string();

                try {
                    JSONObject jsonObject = new JSONObject(jsonData);
                    int value = jsonObject.getInt("value");
                    traffic = value > 600;
                } catch (JSONException e) {
                    Log.e(TAG, "JSON Parsing error: " + e.getMessage());
                }
            }
        });
    }

    public class WifiConnectionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
                ConnectivityManager connManager =
                        (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo networkInfo = connManager.getActiveNetworkInfo();

                if (networkInfo != null &&
                        networkInfo.isConnected() &&
                        networkInfo.getType() == ConnectivityManager.TYPE_WIFI) {

                    WifiManager wifiManager =
                            (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                    String ssid = wifiInfo.getSSID();

                    if (ssid != null && ssid.replace("\"", "").equals("ESP32CAM_HOTSPOT")) {
                        Log.d("WifiReceiver", "✅ ESP32CAM_HOTSPOT에 연결됨!");
                        getTrafficStatus();
                    }
                }
            }
        }
    }
}
