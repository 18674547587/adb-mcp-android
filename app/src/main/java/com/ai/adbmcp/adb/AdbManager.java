package com.ai.adbmcp.adb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.File;
import java.io.IOException;

/** 管理 USB 设备 → ADB 连接的完整生命周期。 */
public final class AdbManager {
    private UsbDeviceConnection usbConn;
    private UsbInterface usbInterface;
    private AdbConnection adb;
    private AdbCrypto crypto;
    private String deviceName = "";
    private String status = "未连接";
    private File keyDir;

    public synchronized String getStatus() { return status; }
    public synchronized String getDeviceName() { return deviceName; }
    public void setKeyDir(File d) { this.keyDir = d; }

    public synchronized boolean isConnected() { return adb != null; }

    public synchronized void connect(UsbManager mgr, UsbDevice dev, long timeoutMs) throws Exception {
        disconnect();
        status = "打开 USB 设备…";

        UsbInterface target = null;
        UsbEndpoint in = null, out = null;
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface itf = dev.getInterface(i);
            if (itf.getInterfaceClass() == 0xFF && itf.getInterfaceSubclass() == 0x42
                    && itf.getInterfaceProtocol() == 0x01) {
                for (int j = 0; j < itf.getEndpointCount(); j++) {
                    UsbEndpoint ep = itf.getEndpoint(j);
                    if (ep.getType() == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.getDirection() == android.hardware.usb.UsbConstants.USB_DIR_IN) in = ep;
                        else out = ep;
                    }
                }
                if (in != null && out != null) { target = itf; break; }
                in = null; out = null;
            }
        }
        if (target == null) throw new IOException("这个 USB 设备不是 ADB 接口（class 0xFF/0x42/0x01）");

        usbConn = mgr.openDevice(dev);
        if (usbConn == null) throw new IOException("openDevice 失败（USB 权限未授予？）");
        if (!usbConn.claimInterface(target, true)) throw new IOException("claimInterface 失败");

        usbInterface = target;
        deviceName = dev.getDeviceName() + " (" + String.format("%04x:%04x", dev.getVendorId(), dev.getProductId()) + ")";
        status = "握手…";

        crypto = (keyDir != null) ? AdbCrypto.loadOrGenerate(keyDir) : AdbCrypto.generate();
        UsbTransport t = new UsbTransport(usbConn, in, out);
        adb = new AdbConnection(t, crypto);
        adb.connect(timeoutMs);
        status = "已连接";
    }

    public synchronized AdbConnection require() throws IOException {
        if (adb == null) throw new IOException("尚未连接设备");
        return adb;
    }

    public synchronized String shell(String cmd) throws IOException {
        return require().shell(cmd, 120000);
    }

    public synchronized byte[] exec(String cmd) throws IOException {
        return require().run("exec:" + cmd, 120000);
    }

    public synchronized void disconnect() {
        if (adb != null) { try { adb.close(); } catch (Exception ignored) { } adb = null; }
        if (usbConn != null) { try { usbConn.releaseInterface(usbInterface); } catch (Exception ignored) { } try { usbConn.close(); } catch (Exception ignored) { } usbConn = null; }
        usbInterface = null;
        status = "未连接";
        deviceName = "";
    }
}
