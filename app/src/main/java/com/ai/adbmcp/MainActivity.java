package com.ai.adbmcp;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;

/** 极简界面：申请 USB 权限 → 建立 ADB 连接 → 起 MCP 服务。 */
public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.ai.adbmcp.USB_PERMISSION";

    private TextView statusView;
    private UsbManager usbManager;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && dev != null) connect(dev);
            else setStatus("USB 权限被拒绝");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad * 2, pad, pad);

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setText("准备就绪");
        root.addView(statusView);

        Button connectBtn = new Button(this);
        connectBtn.setText("① 连接 USB 设备");
        connectBtn.setOnClickListener(v -> pickDevice());
        root.addView(connectBtn);

        Button mcpBtn = new Button(this);
        mcpBtn.setText("② 启动 MCP 服务 (127.0.0.1:7788)");
        mcpBtn.setOnClickListener(v -> startMcp());
        root.addView(mcpBtn);

        setContentView(root);

        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);

        startMcp();
    }

    private void pickDevice() {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices.isEmpty()) {
            setStatus("没有检测到 USB 设备。确认被控端已打开「USB 调试」，并用 OTG 接上。");
            return;
        }
        for (UsbDevice d : devices.values()) {
            if (!isAdb(d)) continue;
            if (usbManager.hasPermission(d)) {
                connect(d);
            } else {
                int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
                PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                        new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);
                setStatus("请求 USB 权限…请在弹窗点「允许」");
                usbManager.requestPermission(d, pi);
            }
            return;
        }
        StringBuilder sb = new StringBuilder("没找到 ADB 接口设备。检测到：\n");
        for (UsbDevice d : devices.values()) {
            sb.append(String.format("· %04x:%04x %s\n", d.getVendorId(), d.getProductId(), d.getDeviceName()));
        }
        sb.append("（被控端需开启 USB 调试，接口 class=0xFF/0x42/0x01）");
        setStatus(sb.toString());
    }

    private boolean isAdb(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            if (itf.getInterfaceClass() == 0xFF && itf.getInterfaceSubclass() == 0x42
                    && itf.getInterfaceProtocol() == 0x01) return true;
        }
        return false;
    }

    private void connect(UsbDevice d) {
        startMcp();
        setStatus("连接中…（若被控端弹出「允许 USB 调试」，请勾选「一律允许」）");
        new Thread(() -> {
            try {
                McpService.ADB.connect(usbManager, d, 60000);
                ui.post(() -> setStatus("已连接：" + McpService.ADB.getDeviceName()
                        + "\nMCP: http://127.0.0.1:7788/mcp"));
            } catch (Exception e) {
                ui.post(() -> setStatus("连接失败：" + e.getMessage()));
            }
        }).start();
    }

    private void startMcp() {
        Intent i = new Intent(this, McpService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void setStatus(String s) {
        ui.post(() -> statusView.setText(s));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (McpService.ADB.isConnected()) {
            statusView.setText("已连接：" + McpService.ADB.getDeviceName() + "\nMCP: http://127.0.0.1:7788/mcp");
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
