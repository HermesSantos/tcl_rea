package tcl.remote;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Self-signed RSA cert. The TV only needs the public key inside it. */
final class Cert {
    final PrivateKey key;
    final X509Certificate cert;

    Cert(PrivateKey key, X509Certificate cert) {
        this.key = key;
        this.cert = cert;
    }

    static Cert create(String name) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        byte[] der = encode(name, pair);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
        cert.verify(pair.getPublic());
        return new Cert(pair.getPrivate(), cert);
    }

    private static byte[] encode(String name, KeyPair pair) throws Exception {
        java.security.interfaces.RSAPublicKey pub = (java.security.interfaces.RSAPublicKey) pair.getPublic();
        byte[] cn = der(0x13, name.getBytes("US-ASCII"));
        byte[] rdn = der(0x30, cat(oid(new int[] {2, 5, 4, 3}), cn));
        byte[] subject = der(0x30, der(0x31, rdn));
        byte[] spkiInner = der(0x30, cat(derInt(pub.getModulus()), derInt(pub.getPublicExponent())));
        byte[] spki = der(0x30, cat(
                der(0x30, cat(oid(new int[] {1, 2, 840, 113549, 1, 1, 1}), new byte[] {0x05, 0x00})),
                der(0x03, cat(new byte[] {0x00}, spkiInner))));
        byte[] alg = der(0x30, cat(oid(new int[] {1, 2, 840, 113549, 1, 1, 11}), new byte[] {0x05, 0x00}));
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        cal.add(Calendar.DAY_OF_YEAR, -1);
        byte[] notBefore = utc(cal);
        cal.add(Calendar.YEAR, 10);
        byte[] notAfter = utc(cal);
        byte[] validity = der(0x30, cat(notBefore, notAfter));
        byte[] version = der(0xA0, der(0x02, new byte[] {2}));
        byte[] serial = der(0x02, new byte[] {0x03, (byte) 0xE8});
        byte[] tbs = der(0x30, cat(version, serial, alg, subject, validity, subject, spki));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(tbs);
        byte[] sig = signer.sign();
        return der(0x30, cat(tbs, alg, der(0x03, cat(new byte[] {0x00}, sig))));
    }

    static byte[] polo(BigInteger value, boolean exponent) {
        String hex = value.toString(16).toUpperCase(Locale.US);
        if (exponent) {
            hex = "0" + hex;
        } else if ((hex.length() & 1) == 1) {
            hex = "0" + hex;
        }
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] utc(Calendar cal) {
        String text = String.format(Locale.US, "%02d%02d%02d%02d%02d%02dZ",
                cal.get(Calendar.YEAR) % 100,
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                cal.get(Calendar.SECOND));
        return der(0x17, text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static byte[] oid(int[] nodes) {
        byte[] body = new byte[32];
        int n = 0;
        body[n++] = (byte) (nodes[0] * 40 + nodes[1]);
        for (int i = 2; i < nodes.length; i++) {
            int v = nodes[i];
            int start = n;
            byte[] tmp = new byte[5];
            int t = 5;
            tmp[--t] = (byte) (v & 0x7f);
            v >>= 7;
            while (v > 0) {
                tmp[--t] = (byte) ((v & 0x7f) | 0x80);
                v >>= 7;
            }
            int len = 5 - t;
            System.arraycopy(tmp, t, body, n, len);
            n += len;
            if (start == n) {
                throw new IllegalStateException("oid");
            }
        }
        byte[] cut = new byte[n];
        System.arraycopy(body, 0, cut, 0, n);
        return der(0x06, cut);
    }

    private static byte[] derInt(BigInteger value) {
        return der(0x02, value.toByteArray());
    }

    private static byte[] der(int tag, byte[] content) {
        byte[] len = length(content.length);
        byte[] out = new byte[1 + len.length + content.length];
        out[0] = (byte) tag;
        System.arraycopy(len, 0, out, 1, len.length);
        System.arraycopy(content, 0, out, 1 + len.length, content.length);
        return out;
    }

    private static byte[] length(int n) {
        if (n < 128) {
            return new byte[] {(byte) n};
        }
        if (n < 256) {
            return new byte[] {(byte) 0x81, (byte) n};
        }
        return new byte[] {(byte) 0x82, (byte) (n >> 8), (byte) n};
    }

    private static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] part : parts) {
            n += part.length;
        }
        byte[] out = new byte[n];
        int i = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, i, part.length);
            i += part.length;
        }
        return out;
    }
}
