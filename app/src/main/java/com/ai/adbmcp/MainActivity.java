package com.ai.adbmcp;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ai.adbmcp.mcp.McpServer;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.List;

/** 主界面：连接状态 / 设备信息 / 快捷操作 / 终端 / MCP 日志。 */
public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.ai.adbmcp.USB_PERMISSION";
    private static final int C_BG = 0xFFF7F8FA, C_CARD = 0xFFFFFFFF, C_TEXT = 0xFF1F2329;
    private static final int C_SUB = 0xFF646A73, C_OK = 0xFF00A870, C_ERR = 0xFFD93026;

    private TextView statusView, deviceView, mcpView, termOut, logView;
    private EditText cmdInput;
    private UsbManager usbManager;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder termBuf = new StringBuilder();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!ACTION_USB_PERMISSION.equals(i.getAction())) return;
            UsbDevice dev = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (ok && dev != null) connect(dev); else setStatus("USB 权限被拒绝", false);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        setContentView(buildUi());

        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);

        startMcp();
        ui.post(logTick);
    }

    // ── UI ────────────────────────────────────────────────
    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, p, p, p);
        scroll.addView(root);

        TextView title = text("ADB MCP", 22, C_TEXT, true);
        root.addView(title);
        TextView sub = text("OTG 连接受控设备 · 能力通过 MCP 暴露", 12, C_SUB, false);
        root.addView(sub);

        // 状态卡
        LinearLayout card = card();
        statusView = text("未连接", 15, C_ERR, true);
        deviceView = text("把被控设备用 OTG 接上，并打开「USB 调试」", 12, C_SUB, false);
        deviceView.setPadding(0, dp(6), 0, 0);
        mcpView = text("MCP: http://127.0.0.1:7788/mcp", 12, C_SUB, false);
        mcpView.setPadding(0, dp(6), 0, 0);
        card.addView(statusView); card.addView(deviceView); card.addView(mcpView);
        root.addView(card);

        // 连接按钮
        LinearLayout r1 = row(root);
        r1.addView(btn("连接 USB 设备", v -> pickDevice()));
        r1.addView(btn("断开", v -> { McpService.ADB.disconnect(); setStatus("已断开", false); refreshInfo(); }));
        r1.addView(btn("复制 MCP 地址", v -> copy(mcpView.getText().toString().replace("MCP: ", ""))));

        // 快捷操作
        root.addView(section("快捷操作"));
        LinearLayout r2 = row(root);
        r2.addView(btn("截图", v -> doScreenshot()));
        r2.addView(btn("返回", v -> key(4)));
        r2.addView(btn("Home", v -> key(3)));
        r2.addView(btn("最近任务", v -> key(187)));
        LinearLayout r3 = row(root);
        r3.addView(btn("锁屏", v -> key(26)));
        r3.addView(btn("音量+", v -> key(24)));
        r3.addView(btn("音量-", v -> key(25)));
        r3.addView(btn("重启", v -> { runAdb("重启", () -> { McpService.ADB.shell("reboot"); return "已发送重启"; }); }));

        // 终端
        root.addView(section("终端"));
        LinearLayout termCard = card();
        cmdInput = new EditText(this);
        cmdInput.setHint("输入 shell 命令，如 pm list packages");
        cmdInput.setTextSize(13);
        cmdInput.setInputType(InputType.TYPE_CLASS_TEXT);
        cmdInput.setSingleLine(true);
        termCard.addView(cmdInput);
        termCard.addView(btn("执行", v -> runCommand()));
        termOut = text("", 11, C_TEXT, false);
        termOut.setTypeface(Typeface.MONOSPACE);
        termOut.setTextIsSelectable(true);
        termOut.setPadding(0, dp(8), 0, 0);
        termCard.addView(termOut);
        root.addView(termCard);

        // 设置
        root.addView(section("设置"));
        LinearLayout cfg = card();
        CheckBox lan = new CheckBox(this);
        lan.setText("允许局域网访问 MCP（0.0.0.0:7788）");
        lan.setTextSize(13);
        lan.setChecked(getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("lan", false));
        lan.setOnCheckedChangeListener((v, on) -> {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("lan", on).apply();
            stopService(new Intent(this, McpService.class));
            ui.postDelayed(this::startMcp, 300);
            mcpView.setText("MCP: http://" + (on ? "0.0.0.0" : "127.0.0.1") + ":7788/mcp");
        });
        cfg.addView(lan);
        root.addView(cfg);

        // MCP 日志
        root.addView(section("MCP 请求日志"));
        LinearLayout logCard = card();
        logView = text("等待客户端连接…", 11, C_SUB, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logCard.addView(logView);
        root.addView(logCard);

        return scroll;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(C_CARD);
        int p = dp(12);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        l.setLayoutParams(lp);
        return l;
    }

    private LinearLayout row(LinearLayout root) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        h.addView(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        root.addView(h, lp);
        return l;
    }

    private TextView section(String s) {
        TextView t = text(s, 13, C_SUB, true);
        t.setPadding(dp(2), dp(4), 0, dp(6));
        return t;
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button btn(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ── 行为 ──────────────────────────────────────────────
    private void pickDevice() {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices.isEmpty()) { setStatus("没有 USB 设备，检查 OTG 与被控端 USB 调试", false); return; }
        for (UsbDevice d : devices.values()) {
            if (!isAdb(d)) continue;
            if (usbManager.hasPermission(d)) connect(d);
            else {
                int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
                PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                        new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);
                setStatus("请求 USB 权限…", false);
                usbManager.requestPermission(d, pi);
            }
            return;
        }
        StringBuilder sb = new StringBuilder("没找到 ADB 接口设备：");
        for (UsbDevice d : devices.values())
            sb.append(String.format(" %04x:%04x", d.getVendorId(), d.getProductId()));
        setStatus(sb.toString(), false);
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
        setStatus("连接中…（被控端弹窗请勾「一律允许」）", false);
        new Thread(() -> {
            try {
                McpService.ADB.connect(usbManager, d, 60000);
                ui.post(() -> { setStatus("已连接", true); refreshInfo(); });
            } catch (Exception e) {
                ui.post(() -> setStatus("连接失败：" + e.getMessage(), false));
            }
        }).start();
    }

    private void refreshInfo() {
        if (!McpService.ADB.isConnected()) {
            deviceView.setText("把被控设备用 OTG 接上，并打开「USB 调试」");
            return;
        }
        new Thread(() -> {
            try {
                String out = McpService.ADB.shell(
                        "echo \"$BOOT\\n\" >/dev/null; "
                        + "getprop ro.product.brand; getprop ro.product.model; "
                        + "getprop ro.build.version.release; getprop ro.build.version.sdk; "
                        + "wm size; dumpsys battery | grep level");
                final String s = out.trim();
                ui.post(() -> deviceView.setText(s.isEmpty() ? McpService.ADB.getDeviceName() : s));
            } catch (Exception e) {
                ui.post(() -> deviceView.setText("设备信息读取失败：" + e.getMessage()));
            }
        }).start();
    }

    private interface Job { String run() throws Exception; }

    private void runAdb(String name, Job job) {
        if (!McpService.ADB.isConnected()) { toast("未连接设备"); return; }
        new Thread(() -> {
            try {
                final String r = job.run();
                ui.post(() -> toast(name + "：" + r));
            } catch (Exception e) {
                ui.post(() -> toast(name + " 失败：" + e.getMessage()));
            }
        }).start();
    }

    private void key(int code) {
        runAdb("按键 " + code, () -> { McpService.ADB.shell("input keyevent " + code); return "ok"; });
    }

    private void doScreenshot() {
        runAdb("截图", () -> {
            byte[] png = McpService.ADB.exec("screencap -p");
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File f = new File(dir, "shot-" + System.currentTimeMillis() + ".png");
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(png); }
            return f.getAbsolutePath() + "（" + png.length / 1024 + " KB）";
        });
    }

    private void runCommand() {
        final String cmd = cmdInput.getText().toString().trim();
        if (cmd.isEmpty()) return;
        if (!McpService.ADB.isConnected()) { toast("未连接设备"); return; }
        appendTerm("$ " + cmd + "\n");
        cmdInput.setText("");
        new Thread(() -> {
            try {
                final String out = McpService.ADB.shell(cmd);
                ui.post(() -> appendTerm(out.isEmpty() ? "(无输出)\n" : out + "\n"));
            } catch (Exception e) {
                ui.post(() -> appendTerm("错误: " + e.getMessage() + "\n"));
            }
        }).start();
    }

    private void appendTerm(String s) {
        termBuf.append(s);
        if (termBuf.length() > 20000) termBuf.delete(0, termBuf.length() - 20000);
        termOut.setText(termBuf);
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("mcp", s));
        toast("已复制：" + s);
    }

    private void startMcp() {
        Intent i = new Intent(this, McpService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void setStatus(String s, boolean ok) {
        statusView.setText(s);
        statusView.setTextColor(ok ? C_OK : C_ERR);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private final Runnable logTick = new Runnable() {
        @Override public void run() {
            List<String> logs = McpServer.recentLogs();
            StringBuilder sb = new StringBuilder();
            if (logs.isEmpty()) sb.append("等待客户端连接 ").append("http://127.0.0.1:7788/mcp");
            else for (String l : logs) sb.append(l).append('\n');
            logView.setText(sb);
            ui.postDelayed(this, 1000);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        if (McpService.ADB.isConnected()) { setStatus("已连接", true); refreshInfo(); }
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) { }
        ui.removeCallbacks(logTick);
        super.onDestroy();
    }
}
