package com.ai.adbmcp.mcp;

import android.util.Base64;
import android.util.Log;

import com.ai.adbmcp.adb.AdbManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** 极简 MCP over HTTP：POST /mcp 收 JSON-RPC，GET /health 探活。 */
public final class McpServer {
    private static final String TAG = "AdbMcp";

    private final int port;
    private final String bindAddr;
    private final AdbManager adb;
    private final McpTools tools;

    private ServerSocket serverSocket;
    private volatile boolean running;
    private Thread acceptThread;

    public McpServer(int port, AdbManager adb, String bindAddr) {
        this.port = port;
        this.bindAddr = bindAddr;
        this.adb = adb;
        this.tools = new McpTools(adb);
    }

    public boolean isRunning() { return running; }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName(bindAddr), port));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "mcp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "MCP listening on " + bindAddr + ":" + port);
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                Thread t = new Thread(() -> handle(s), "mcp-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept: " + e.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(180000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";

            int contentLength = 0;
            boolean wantsSse = false;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx <= 0) continue;
                String hk = line.substring(0, idx).trim();
                String hv = line.substring(idx + 1).trim();
                if (hk.equalsIgnoreCase("Content-Length")) {
                    try { contentLength = Integer.parseInt(hv); } catch (NumberFormatException ignored) { }
                } else if (hk.equalsIgnoreCase("Accept") && hv.contains("text/event-stream")) {
                    wantsSse = true;
                }
            }

            if ("GET".equals(method) && !path.startsWith("/health") && !path.startsWith("/mcp")) {
                respond(out, "{\"ok\":true,\"mcp\":\"POST /mcp\",\"health\":\"/health\"}");
                return;
            }

            if ("GET".equals(method)) {
                JSONObject health = new JSONObject();
                health.put("ok", true);
                health.put("name", "adb-mcp-android");
                health.put("version", "1.0.0");
                health.put("status", adb.getStatus());
                health.put("device", adb.getDeviceName());
                health.put("tools", McpTools.tools().length());
                respond(out, health.toString());
                return;
            }

            byte[] body = new byte[Math.max(0, Math.min(contentLength, 32 * 1024 * 1024))];
            int read = 0;
            while (read < body.length) {
                int n = in.read(body, read, body.length - read);
                if (n < 0) break;
                read += n;
            }
            String json = process(new String(body, 0, read, StandardCharsets.UTF_8));
            if (wantsSse) respondSse(out, json); else respond(out, json);
        } catch (Exception e) {
            Log.w(TAG, "handle: " + e);
        }
    }

    private void respond(OutputStream out, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private void respondSse(OutputStream out, String json) throws IOException {
        byte[] b = ("event: message\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream; charset=utf-8\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
            if (buf.size() > 65536) break;
        }
        if (c == -1 && buf.size() == 0) return null;
        return buf.toString("UTF-8");
    }

    private String process(String body) {
        JSONObject resp = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String method = req.optString("method", "");
            Object id = req.opt("id");
            resp.put("jsonrpc", "2.0");
            if (id != null) resp.put("id", id);

            if ("initialize".equals(method)) {
                JSONObject params = req.optJSONObject("params");
                JSONObject result = new JSONObject();
                result.put("protocolVersion", params != null ? params.optString("protocolVersion", "2024-11-05") : "2024-11-05");
                result.put("capabilities", new JSONObject().put("tools", new JSONObject()));
                result.put("serverInfo", new JSONObject().put("name", "adb-mcp-android").put("version", "1.0.0"));
                resp.put("result", result);
            } else if ("tools/list".equals(method)) {
                resp.put("result", new JSONObject().put("tools", McpTools.tools()));
            } else if ("ping".equals(method)) {
                resp.put("result", new JSONObject());
            } else if ("tools/call".equals(method)) {
                JSONObject p = req.optJSONObject("params");
                String name = p != null ? p.optString("name") : "";
                JSONObject args = p != null ? p.optJSONObject("arguments") : null;
                McpTools.Result r = tools.call(name, args == null ? new JSONObject() : args);

                JSONArray content = new JSONArray();
                if (r.image != null) {
                    content.put(new JSONObject().put("type", "image")
                            .put("data", Base64.encodeToString(r.image, Base64.NO_WRAP))
                            .put("mimeType", "image/png"));
                } else {
                    content.put(new JSONObject().put("type", "text").put("text", r.text == null ? "" : r.text));
                }
                resp.put("result", new JSONObject().put("content", content).put("isError", !r.ok));
            } else if (method.startsWith("notifications/")) {
                return "{}";
            } else {
                resp.put("error", new JSONObject().put("code", -32601).put("message", "unknown method " + method));
            }
        } catch (Exception e) {
            try {
                resp = new JSONObject();
                resp.put("jsonrpc", "2.0");
                resp.put("id", JSONObject.NULL);
                resp.put("error", new JSONObject().put("code", -32700).put("message", "parse error"));
            } catch (Exception ignored) { }
        }
        return resp.toString();
    }
}
