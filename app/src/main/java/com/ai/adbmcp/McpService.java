package com.ai.adbmcp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.ai.adbmcp.adb.AdbManager;
import com.ai.adbmcp.mcp.McpServer;

/** 前台服务：保活 + 常驻 MCP HTTP 服务。 */
public class McpService extends Service {
    public static final int PORT = 7788;
    public static final AdbManager ADB = new AdbManager();
    private static final String CHANNEL = "adbmcp";

    private McpServer server;

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();
        try {
            server = new McpServer(PORT, ADB);
            server.start();
        } catch (Exception e) {
            Log.e("AdbMcp", "MCP 启动失败", e);
        }
    }

    private void startAsForeground() {
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "ADB MCP", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            n = new Notification.Builder(this, CHANNEL)
                    .setContentTitle("ADB MCP 运行中")
                    .setContentText("MCP: 127.0.0.1:" + PORT)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setOngoing(true)
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setContentTitle("ADB MCP 运行中")
                    .setContentText("MCP: 127.0.0.1:" + PORT)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setOngoing(true)
                    .build();
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        ADB.disconnect();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
