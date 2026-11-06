package com.ai.adbmcp.adb;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;

/** ADB 认证用的 RSA 密钥：签名 TOKEN、导出 Android 格式公钥。 */
public final class AdbCrypto {
    private final KeyPair keyPair;
    private byte[] blob;

    private AdbCrypto(KeyPair kp) { this.keyPair = kp; }

    /** 密钥落盘复用：设备只需授权一次，之后自动信任。 */
    public static AdbCrypto loadOrGenerate(File dir) throws Exception {
        File privF = new File(dir, "adbkey.pk8");
        File pubF = new File(dir, "adbkey.pub");
        if (privF.isFile() && pubF.isFile()) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(privF)));
                PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(readAll(pubF)));
                return new AdbCrypto(new KeyPair(pub, priv));
            } catch (Exception ignored) {
                // 文件损坏 → 重新生成
            }
        }
        AdbCrypto c = generate();
        dir.mkdirs();
        writeAll(privF, c.keyPair.getPrivate().getEncoded());
        writeAll(pubF, c.keyPair.getPublic().getEncoded());
        return c;
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return b;
        }
    }

    private static void writeAll(File f, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
    }

    public static AdbCrypto generate() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return new AdbCrypto(g.generateKeyPair());
    }

    public byte[] sign(byte[] token) throws Exception {
        Signature s = Signature.getInstance("SHA1withRSA");
        s.initSign(keyPair.getPrivate());
        s.update(token);
        return s.sign();
    }

    /** Android adbd 认的公钥格式：len + n0inv + modulus(LE) + rr(LE) + exponent。 */
    public synchronized byte[] publicKeyBlob() {
        if (blob != null) return blob;
        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        BigInteger n = pub.getModulus();
        BigInteger e = pub.getPublicExponent();
        BigInteger mask = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(mask).modInverse(mask).negate().mod(mask);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);

        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(524);
        b.putInt(n0inv.intValue());
        b.put(le(n, 256));
        b.put(le(rr, 256));
        b.putInt(e.intValue());
        blob = b.array();
        return blob;
    }

    private static byte[] le(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }
}
