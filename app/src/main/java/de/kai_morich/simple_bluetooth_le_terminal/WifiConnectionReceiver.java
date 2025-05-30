package de.kai_morich.simple_bluetooth_le_terminal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

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
                // 안드로이드에서 SSID는 종종 쌍따옴표로 감싸져 있습니다
                if (ssid != null && ssid.replace("\"", "").equals("ESP32CAM_HOSPOT")) {
                    Log.d("WiFiReceiver", "ESP32CAM_HOSPOT에 연결됨!");
                    // 여기에 연결 성공시 처리할 코드 작성
                }
            }
        }
    }


}
