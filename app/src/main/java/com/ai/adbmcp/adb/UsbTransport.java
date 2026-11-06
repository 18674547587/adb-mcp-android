package com.ai.adbmcp.adb;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;

import java.io.IOException;

/** USB bulk 端点上的字节流。 */
public final class UsbTransport {
    private static final int TIMEOUT = 10000;
    private static final int CHUNK = 16384;

    private final UsbDeviceConnection conn;
    private final UsbEndpoint in;
    private final UsbEndpoint out;

    public UsbTransport(UsbDeviceConnection c, UsbEndpoint in, UsbEndpoint out) {
        this.conn = c;
        this.in = in;
        this.out = out;
    }

    public void readFully(byte[] dst, int len) throws IOException {
        int got = 0;
        byte[] tmp = new byte[CHUNK];
        while (got < len) {
            int want = Math.min(CHUNK, len - got);
            int n = conn.bulkTransfer(in, tmp, want, TIMEOUT);
            if (n <= 0) throw new IOException("USB 读失败 (" + n + ")");
            System.arraycopy(tmp, 0, dst, got, n);
            got += n;
        }
    }

    public void write(byte[] data, int len) throws IOException {
        int sent = 0;
        byte[] tmp = new byte[CHUNK];
        while (sent < len) {
            int n = Math.min(CHUNK, len - sent);
            System.arraycopy(data, sent, tmp, 0, n);
            int w = conn.bulkTransfer(out, tmp, n, TIMEOUT);
            if (w <= 0) throw new IOException("USB 写失败 (" + w + ")");
            sent += w;
        }
    }

    public void close() {
        try { conn.close(); } catch (Exception ignored) { }
    }
}
