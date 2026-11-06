package com.ai.adbmcp.adb;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 一条 ADB 流（对应一个 shell/exec 会话）。 */
public final class AdbStream {
    final int localId;
    volatile int remoteId = -1;

    private final AdbConnection conn;
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private final CountDownLatch openLatch = new CountDownLatch(1);
    private final Semaphore writeAck = new Semaphore(1);
    private boolean closed;
    private IOException error;

    AdbStream(int localId, AdbConnection conn) {
        this.localId = localId;
        this.conn = conn;
    }

    void onOkay(int remote, boolean opening) {
        if (opening) {
            this.remoteId = remote;
            openLatch.countDown();
        } else {
            writeAck.release();
        }
    }

    void onData(byte[] data) {
        synchronized (queue) {
            queue.add(data);
            queue.notifyAll();
        }
    }

    void onClose() {
        synchronized (queue) {
            closed = true;
            queue.notifyAll();
        }
    }

    void onError(IOException e) {
        synchronized (queue) {
            error = e;
            closed = true;
            queue.notifyAll();
        }
    }

    boolean awaitOpen(long ms) throws IOException {
        try {
            if (!openLatch.await(ms, TimeUnit.MILLISECONDS)) throw new IOException("打开流超时");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("打开流被中断");
        }
    }

    /** 返回 -1 表示流结束；超时抛 IOException。 */
    public int read(byte[] buf, int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMs);
        synchronized (queue) {
            while (queue.isEmpty() && !closed) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) throw new IOException("读超时");
                try {
                    queue.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("读被中断");
                }
            }
            if (error != null) throw error;
            byte[] d = queue.poll();
            if (d == null) return -1;
            int n = Math.min(d.length, buf.length);
            System.arraycopy(d, 0, buf, 0, n);
            return n;
        }
    }

    public void write(byte[] data) throws IOException {
        try {
            if (!writeAck.tryAcquire(15, TimeUnit.SECONDS)) throw new IOException("写流控超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("写被中断");
        }
        conn.send(AdbConnection.A_WRTE, remoteId, localId, data);
    }

    public void close() {
        try { conn.send(AdbConnection.A_CLSE, remoteId, localId, new byte[0]); } catch (Exception ignored) { }
        conn.removeStream(localId);
    }
}
