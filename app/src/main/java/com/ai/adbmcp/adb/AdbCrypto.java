package com.ai.adbmcp.adb;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;

/** ADB 认证用的 RSA 密钥：签名 TOKEN、导出 Android 格式公钥。 */
public final class AdbCrypto {
    private final KeyPair keyPair;
    private byte[] blob;

    private AdbCrypto(KeyPair kp) { this.keyPair = kp; }

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
