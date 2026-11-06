package com.ai.adbmcp.adb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** ADB over USB 协议：CNXN / AUTH / OPEN / OKAY / WRTE / CLSE。 */
public final class AdbConnection {
    public static final int A_CNXN = 0x4e584e43;
    public static final int A_AUTH = 0x48545541;
    public static final int A_OPEN = 0x4e45504f;
    public static final int A_OKAY = 0x59414b4f;
    public static final int A_CLSE = 0x45534c43;
    public static final int A_WRTE = 0x45545257;

    private static final int VERSION = 0x01000001;
    private static final int MAX_PAYLOAD = 16384;

    private final UsbTransport transport;
    private final AdbCrypto crypto;
    private final Map<Integer, AdbStream> streams = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final CountDownLatch connected = new CountDownLatch(1);

    private volatile boolean running = true;
    private volatile boolean sentSignature = false;
    private volatile String lastError;

    public AdbConnection(UsbTransport t, AdbCrypto c) {
        this.transport = t;
        this.crypto = c;
    }

    public void connect(long timeoutMs) throws IOException {
        Thread reader = new Thread(this::readLoop, "adb-reader");
        reader.setDaemon(true);
        reader.start();

        send(A_CNXN, VERSION, MAX_PAYLOAD, "host::\0".getBytes());

        try {
            if (!connected.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IOException("握手超时"
                        + (lastError != null ? "：" + lastError : "（看设备屏幕是否弹出「允许 USB 调试」）"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("握手被中断");
        }
    }

    private void readLoop() {
        byte[] hdr = new byte[24];
        try {
            while (running) {
                transport.readFully(hdr, 24);
                ByteBuffer b = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
                int cmd = b.getInt();
                int arg0 = b.getInt();
                int arg1 = b.getInt();
                int len = b.getInt();
                b.getInt(); // checksum
                int magic = b.getInt();

                if ((cmd ^ 0xffffffff) != magic) {
                    lastError = "包 magic 校验失败";
                    continue;
                }
                int n = Math.max(0, Math.min(len, 4 * 1024 * 1024));
                byte[] data = new byte[n];
                if (n > 0) transport.readFully(data, n);
                handle(cmd, arg0, arg1, data);
            }
        } catch (Throwable t) {
            lastError = t.getMessage();
            IOException e = new IOException("连接中断：" + t.getMessage());
            for (AdbStream s : streams.values()) s.onError(e);
            streams.clear();
        }
    }

    private void handle(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        switch (cmd) {
            case A_CNXN:
                connected.countDown();
                break;
            case A_AUTH:
                if (arg0 == 1) { // TOKEN
                    if (!sentSignature) {
                        sentSignature = true;
                        try {
                            send(A_AUTH, 2, 0, crypto.sign(data));
                        } catch (Exception e) {
                            throw new IOException("签名失败: " + e.getMessage());
                        }
                    } else {
                        send(A_AUTH, 3, 0, crypto.publicKeyBlob());
                    }
                }
                break;
            case A_OKAY: {
                AdbStream s = streams.get(arg1);
                if (s != null) s.onOkay(arg0, s.remoteId < 0);
                break;
            }
            case A_WRTE: {
                AdbStream s = streams.get(arg1);
                if (s != null) {
                    s.onData(data);
                    send(A_OKAY, arg1, arg0, new byte[0]);
                }
                break;
            }
            case A_CLSE: {
                AdbStream s = streams.get(arg1);
                if (s != null) s.onClose();
                break;
            }
            default:
                break;
        }
    }

    public synchronized void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte x : data) sum += (x & 0xff);
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(cmd ^ 0xffffffff);
        b.put(data);
        byte[] out = b.array();
        transport.write(out, out.length);
    }

    public AdbStream open(String destination) throws IOException {
        int id = nextId.getAndIncrement();
        AdbStream s = new AdbStream(id, this);
        streams.put(id, s);
        send(A_OPEN, id, 0, (destination + "\0").getBytes());
        try {
            s.awaitOpen(30000);
        } catch (IOException e) {
            streams.remove(id);
            throw e;
        }
        return s;
    }

    void removeStream(int localId) {
        streams.remove(localId);
    }

    /** 跑一个服务（shell: / exec:），拿回全部输出。 */
    public byte[] run(String service, long timeoutMs) throws IOException {
        AdbStream s = open(service);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                int n;
                try {
                    n = s.read(buf, (int) left);
                } catch (IOException e) {
                    break;
                }
                if (n < 0) break;
                out.write(buf, 0, n);
                if (out.size() > 64 * 1024 * 1024) break;
            }
        } finally {
            s.close();
        }
        return out.toByteArray();
    }

    public String shell(String cmd, long timeoutMs) throws IOException {
        return new String(run("shell:" + cmd, timeoutMs), "UTF-8").replace("\r\n", "\n");
    }

    public void close() {
        running = false;
        for (AdbStream s : streams.values()) s.onClose();
        streams.clear();
        transport.close();
    }
}
