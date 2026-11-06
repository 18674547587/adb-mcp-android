package com.ai.adbmcp.mcp;

import com.ai.adbmcp.adb.AdbManager;
import com.ai.adbmcp.adb.AdbStream;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import android.util.Base64;

/** MCP 工具表 + 实现。 */
public final class McpTools {

    public static final class Result {
        public final boolean ok;
        public final String text;
        public final byte[] image;

        Result(boolean ok, String text, byte[] image) {
            this.ok = ok;
            this.text = text;
            this.image = image;
        }

        static Result text(String s) { return new Result(true, s, null); }
        static Result image(byte[] b) { return new Result(true, "screenshot", b); }
        static Result fail(String s) { return new Result(false, s, null); }
    }

    private static final String TOOLS = "[" +
        "{\"name\":\"adb_status\",\"description\":\"查看 USB/ADB 连接状态与 MCP 端口\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}," +
        "{\"name\":\"adb_shell\",\"description\":\"在设备上执行 shell 命令（uid=2000）\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}},\"required\":[\"command\"]}}," +
        "{\"name\":\"adb_device_info\",\"description\":\"设备信息：品牌/型号/系统/序列号/屏幕/电量/存储\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}," +
        "{\"name\":\"adb_props\",\"description\":\"读取系统属性\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"}}}}," +
        "{\"name\":\"adb_list_packages\",\"description\":\"列出已安装包名\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"filter\":{\"type\":\"string\"}}}}," +
        "{\"name\":\"adb_package_info\",\"description\":\"包详情\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"}},\"required\":[\"package\"]}}," +
        "{\"name\":\"adb_install\",\"description\":\"安装 APK（传 base64 内容）\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"apk_base64\":{\"type\":\"string\"}},\"required\":[\"apk_base64\"]}}," +
        "{\"name\":\"adb_uninstall\",\"description\":\"卸载应用\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"},\"keep_data\":{\"type\":\"boolean\"}},\"required\":[\"package\"]}}," +
        "{\"name\":\"adb_start_app\",\"description\":\"启动应用\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"},\"activity\":{\"type\":\"string\"}},\"required\":[\"package\"]}}," +
        "{\"name\":\"adb_stop_app\",\"description\":\"强制停止应用\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"}},\"required\":[\"package\"]}}," +
        "{\"name\":\"adb_clear_data\",\"description\":\"清除应用数据\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"}},\"required\":[\"package\"]}}," +
        "{\"name\":\"adb_screenshot\",\"description\":\"截屏，返回 PNG\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}," +
        "{\"name\":\"adb_tap\",\"description\":\"点击坐标\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"}},\"required\":[\"x\",\"y\"]}}," +
        "{\"name\":\"adb_swipe\",\"description\":\"滑动\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"x2\":{\"type\":\"number\"},\"y2\":{\"type\":\"number\"},\"ms\":{\"type\":\"number\"}},\"required\":[\"x\",\"y\",\"x2\",\"y2\"]}}," +
        "{\"name\":\"adb_text\",\"description\":\"输入文本\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}}," +
        "{\"name\":\"adb_key\",\"description\":\"按键，如 HOME/BACK/ENTER/3\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"}},\"required\":[\"key\"]}}," +
        "{\"name\":\"adb_screen_size\",\"description\":\"分辨率与密度\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}," +
        "{\"name\":\"adb_logcat\",\"description\":\"抓取 logcat\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"lines\":{\"type\":\"number\"},\"filter\":{\"type\":\"string\"}}}}," +
        "{\"name\":\"adb_dumpsys\",\"description\":\"dumpsys 服务信息\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"service\":{\"type\":\"string\"}},\"required\":[\"service\"]}}," +
        "{\"name\":\"adb_ls\",\"description\":\"列目录\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}}," +
        "{\"name\":\"adb_pull\",\"description\":\"读取设备文件（返回 base64，限 8MB）\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}}," +
        "{\"name\":\"adb_push\",\"description\":\"写入设备文件（base64 内容）\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content_base64\":{\"type\":\"string\"}},\"required\":[\"path\",\"content_base64\"]}}," +
        "{\"name\":\"adb_reboot\",\"description\":\"重启设备\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"mode\":{\"type\":\"string\"}}}}" +
        "]";

    public static JSONArray tools() {
        try { return new JSONArray(TOOLS); } catch (Exception e) { return new JSONArray(); }
    }

    private final AdbManager adb;

    public McpTools(AdbManager adb) { this.adb = adb; }

    public Result call(String name, JSONObject a) {
        try {
            switch (name) {
                case "adb_status":
                    return Result.text("连接状态: " + adb.getStatus()
                            + "\n设备: " + (adb.getDeviceName().isEmpty() ? "-" : adb.getDeviceName()));

                case "adb_shell":
                    return Result.text(adb.shell(a.getString("command")));

                case "adb_device_info": {
                    String brand = adb.shell("getprop ro.product.brand").trim();
                    String model = adb.shell("getprop ro.product.model").trim();
                    String rel = adb.shell("getprop ro.build.version.release").trim();
                    String sdk = adb.shell("getprop ro.build.version.sdk").trim();
                    String sn = adb.shell("getprop ro.serialno").trim();
                    String abi = adb.shell("getprop ro.product.cpu.abi").trim();
                    String size = adb.shell("wm size").trim();
                    String den = adb.shell("wm density").trim();
                    String bat = adb.shell("dumpsys battery | grep -E 'level|status|temperature'").trim();
                    String df = adb.shell("df -h /data | tail -1").trim();
                    return Result.text("品牌型号: " + brand + " " + model
                            + "\n系统: Android " + rel + " (SDK " + sdk + ")"
                            + "\n序列号: " + sn + "\nABI: " + abi
                            + "\n屏幕: " + size + " / " + den
                            + "\n电池: " + bat.replace("\n", " ")
                            + "\n存储: " + df);
                }

                case "adb_props": {
                    String key = a.optString("key", "");
                    return Result.text(adb.shell(key.isEmpty() ? "getprop" : "getprop " + key));
                }

                case "adb_list_packages": {
                    String f = a.optString("filter", "");
                    String out = adb.shell("pm list packages " + f);
                    return Result.text(out.replace("package:", ""));
                }

                case "adb_package_info":
                    return Result.text(adb.shell("dumpsys package " + a.getString("package")
                            + " | grep -E 'versionName|versionCode|minSdk|targetSdk|codePath|firstInstallTime' | head -8"));

                case "adb_install": {
                    byte[] apk = Base64.decode(a.getString("apk_base64"), Base64.DEFAULT);
                    if (apk.length > 128 * 1024 * 1024) return Result.fail("APK 超过 128MB");
                    writeFile("/data/local/tmp/adbmcp-install.apk", apk);
                    String out = adb.shell("pm install -r /data/local/tmp/adbmcp-install.apk");
                    adb.shell("rm -f /data/local/tmp/adbmcp-install.apk");
                    return Result.text(out);
                }

                case "adb_uninstall":
                    return Result.text(adb.shell("pm uninstall "
                            + (a.optBoolean("keep_data") ? "-k " : "") + a.getString("package")));

                case "adb_start_app": {
                    String act = a.optString("activity", "");
                    String cmd = act.isEmpty()
                            ? "monkey -p " + a.getString("package") + " -c android.intent.category.LAUNCHER 1"
                            : "am start -n " + a.getString("package") + "/" + act;
                    return Result.text(adb.shell(cmd));
                }

                case "adb_stop_app":
                    return Result.text(adb.shell("am force-stop " + a.getString("package")));

                case "adb_clear_data":
                    return Result.text(adb.shell("pm clear " + a.getString("package")));

                case "adb_screenshot": {
                    byte[] png = adb.exec("screencap -p");
                    if (png.length < 8) return Result.fail("截图失败（返回 " + png.length + " 字节）");
                    return Result.image(png);
                }

                case "adb_tap":
                    return Result.text(adb.shell("input tap " + a.getInt("x") + " " + a.getInt("y")));

                case "adb_swipe":
                    return Result.text(adb.shell("input swipe " + a.getInt("x") + " " + a.getInt("y")
                            + " " + a.getInt("x2") + " " + a.getInt("y2") + " " + a.optInt("ms", 300)));

                case "adb_text":
                    return Result.text(adb.shell("input text '" + a.getString("text").replace(" ", "%s") + "'"));

                case "adb_key":
                    return Result.text(adb.shell("input keyevent " + a.getString("key")));

                case "adb_screen_size":
                    return Result.text(adb.shell("wm size; wm density"));

                case "adb_logcat": {
                    int n = a.optInt("lines", 200);
                    String f = a.optString("filter", "");
                    return Result.text(adb.shell("logcat -d -t " + n + (f.isEmpty() ? "" : " -s " + f)));
                }

                case "adb_dumpsys":
                    return Result.text(adb.shell("dumpsys " + a.getString("service") + " | head -200"));

                case "adb_ls":
                    return Result.text(adb.shell("ls -la " + a.optString("path", "/sdcard")));

                case "adb_pull": {
                    byte[] d = adb.exec("cat " + a.getString("path"));
                    if (d.length > 8 * 1024 * 1024) return Result.fail("文件超过 8MB，改用 shell 处理");
                    return Result.text(Base64.encodeToString(d, Base64.NO_WRAP));
                }

                case "adb_push": {
                    byte[] d = Base64.decode(a.getString("content_base64"), Base64.DEFAULT);
                    writeFile(a.getString("path"), d);
                    return Result.text("已写入 " + d.length + " 字节到 " + a.getString("path"));
                }

                case "adb_reboot": {
                    String mode = a.optString("mode", "");
                    return Result.text(adb.shell("reboot " + mode));
                }

                default:
                    return Result.fail("未知工具 " + name);
            }
        } catch (Exception e) {
            return Result.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void writeFile(String path, byte[] data) throws Exception {
        AdbStream s = adb.require().open("exec:cat > " + path);
        try {
            int off = 0;
            while (off < data.length) {
                int n = Math.min(16384, data.length - off);
                byte[] chunk = new byte[n];
                System.arraycopy(data, off, chunk, 0, n);
                s.write(chunk);
                off += n;
            }
        } finally {
            s.close();
        }
    }
}
