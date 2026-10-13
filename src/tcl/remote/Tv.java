package tcl.remote;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Locale;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Android TV remote protocol v2. Pairing is port 6467, keys and voice are port 6466.
 */
final class Tv {
    interface Listener {
        void status(String text);
        void needPin();
        void ready(boolean on);
        void hearing(boolean on);
    }

    private static final String TAG = "TclRemote";
    private static final int PAIR = 6467;
    private static final int CMD = 6466;
    private static final int FEATURES = 1 | 2 | 4 | 8 | 32 | 64 | 512;
    private static final int VOICE_CHUNK = 8 * 1024;

    private final File dir;
    final Listener listener;
    private Cert identity;
    private SSLSocket socket;
    private OutputStream output;
    private Thread reader;
    private volatile boolean runReader;
    private volatile boolean ready;
    private int features = FEATURES;
    private final Object writeLock = new Object();
    private final Object pinLock = new Object();
    private final Object voiceLock = new Object();
    private String pinCode;
    private boolean pinReady;
    private int voiceSession = -1;
    private boolean voiceBegan;
    private volatile boolean wantVoice;
    private Thread voiceThread;

    Tv(File dir, Listener listener) {
        this.dir = dir;
        this.listener = listener;
    }

    void connect(String host) {
        new Thread(() -> {
            try {
                openCommand(host);
            } catch (Exception first) {
                Log.i(TAG, "comando falhou, pareando", first);
                try {
                    pair(host);
                    openCommand(host);
                } catch (Exception failed) {
                    Log.i(TAG, "pareamento falhou", failed);
                    listener.status(failed.getMessage() == null ? "não conectou" : failed.getMessage());
                    listener.ready(false);
                }
            }
        }, "tv-connect").start();
    }

    void submitPin(String pin) {
        synchronized (pinLock) {
            pinCode = pin;
            pinReady = true;
            pinLock.notifyAll();
        }
    }

    void key(int code) {
        if (!ready) {
            listener.status("ainda não conectou");
            return;
        }
        try {
            send(new Pb().raw(10, new Pb().varint(1, code).varint(2, 3).done()).done());
        } catch (Exception e) {
            listener.status("caiu a conexão");
            listener.ready(false);
            ready = false;
        }
    }

    void voice(boolean on) {
        if (on) {
            if (voiceThread != null && voiceThread.isAlive()) {
                return;
            }
            wantVoice = true;
            voiceThread = new Thread(this::runVoice, "tv-voice");
            voiceThread.start();
        } else {
            wantVoice = false;
        }
    }

    void shutdown() {
        wantVoice = false;
        submitPin(null);
        stopReader();
    }

    private void openCommand(String host) throws Exception {
        stopReader();
        listener.status("conectando...");
        listener.ready(false);
        ready = false;
        SSLSocket opened = tls(host, CMD);
        socket = opened;
        output = opened.getOutputStream();
        runReader = true;
        reader = new Thread(this::readLoop, "tv-read");
        reader.start();
        long end = System.currentTimeMillis() + 4000;
        while (!ready && runReader && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        if (!ready) {
            stopReader();
            throw new Exception("a TV não aceitou. olhe se o código apareceu");
        }
        listener.status("conectado");
        listener.ready(true);
    }

    private void pair(String host) throws Exception {
        listener.status("pareando...");
        SSLSocket pairing = tls(host, PAIR);
        try {
            OutputStream out = pairing.getOutputStream();
            InputStream in = pairing.getInputStream();
            write(out, outer(10, new Pb().text(1, "atvremote").text(2, "Celular").done()));
            byte[] ack = readMessage(in);
            if (Pb.Reader.payload(ack, 11) == null) {
                throw new Exception("a TV recusou o pareamento");
            }
            byte[] encoding = new Pb().varint(1, 3).varint(2, 6).done();
            write(out, outer(20, new Pb().raw(1, encoding).varint(3, 1).done()));
            byte[] options = readMessage(in);
            if (Pb.Reader.payload(options, 20) == null) {
                throw new Exception("a TV recusou as opções");
            }
            write(out, outer(30, new Pb().raw(1, encoding).varint(2, 1).done()));
            byte[] configured = readMessage(in);
            if (Pb.Reader.payload(configured, 31) == null) {
                throw new Exception("a TV não mostrou o código");
            }
            listener.status("digite o código que apareceu na TV");
            listener.needPin();
            String pin = waitPin();
            if (pin == null) {
                throw new Exception("pareamento cancelado");
            }
            pin = pin.trim().toUpperCase(Locale.US).replace(" ", "");
            if (!pin.matches("[0-9A-F]{6}")) {
                throw new Exception("o código tem 6 caracteres hexadecimais");
            }
            byte[] hash = secret(pairing, pin);
            if ((hash[0] & 0xff) != Integer.parseInt(pin.substring(0, 2), 16)) {
                throw new Exception("código não confere");
            }
            write(out, outer(40, new Pb().raw(1, hash).done()));
            byte[] done = readMessage(in);
            int status = Pb.Reader.field(done, 2);
            if (status != 200 || Pb.Reader.payload(done, 41) == null) {
                throw new Exception("a TV não aceitou o código");
            }
            listener.status("pareado");
        } finally {
            try {
                pairing.close();
            } catch (Exception ignored) {
            }
        }
    }

    private byte[] secret(SSLSocket pairing, String pin) throws Exception {
        X509Certificate server = (X509Certificate) pairing.getSession().getPeerCertificates()[0];
        RSAPublicKey client = (RSAPublicKey) identity.cert.getPublicKey();
        RSAPublicKey remote = (RSAPublicKey) server.getPublicKey();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Cert.polo(client.getModulus(), false));
        digest.update(Cert.polo(client.getPublicExponent(), true));
        digest.update(Cert.polo(remote.getModulus(), false));
        digest.update(Cert.polo(remote.getPublicExponent(), true));
        digest.update(hex(pin.substring(2)));
        return digest.digest();
    }

    private void readLoop() {
        try {
            InputStream in = socket.getInputStream();
            while (runReader) {
                byte[] message = readMessage(in);
                byte[] configure = Pb.Reader.payload(message, 1);
                if (configure != null) {
                    int supported = Pb.Reader.field(configure, 1);
                    features = FEATURES & (supported == 0 ? FEATURES : supported);
                    byte[] info = new Pb()
                            .varint(3, 1)
                            .text(4, "1")
                            .text(5, "atvremote")
                            .text(6, "1.0.0")
                            .done();
                    send(new Pb().raw(1, new Pb().varint(1, features).raw(2, info).done()).done());
                    continue;
                }
                if (Pb.Reader.payload(message, 2) != null) {
                    send(new Pb().raw(2, new Pb().varint(1, features).done()).done());
                    continue;
                }
                byte[] ping = Pb.Reader.payload(message, 8);
                if (ping != null) {
                    int val = Pb.Reader.field(ping, 1);
                    send(new Pb().raw(9, val == 0 ? new byte[0] : new Pb().varint(1, val).done()).done());
                    continue;
                }
                byte[] voice = Pb.Reader.payload(message, 30);
                if (voice != null) {
                    synchronized (voiceLock) {
                        voiceSession = Pb.Reader.field(voice, 1);
                        voiceBegan = true;
                        voiceLock.notifyAll();
                    }
                    continue;
                }
                if (Pb.Reader.payload(message, 40) != null) {
                    ready = true;
                }
            }
        } catch (Exception e) {
            if (runReader) {
                Log.i(TAG, "leitura encerrou", e);
                runReader = false;
                boolean wasReady = ready;
                ready = false;
                listener.ready(false);
                if (wasReady) {
                    listener.status("conexão caiu");
                }
            }
        }
    }

    private void runVoice() {
        listener.hearing(true);
        AudioRecord mic = null;
        try {
            if (!ready) {
                listener.status("conecte primeiro");
                return;
            }
            if ((features & 8) == 0) {
                listener.status("essa TV não aceitou voz");
                return;
            }
            synchronized (voiceLock) {
                voiceSession = -1;
                voiceBegan = false;
            }
            send(new Pb().raw(10, new Pb().varint(1, 84).varint(2, 3).done()).done());
            int session;
            synchronized (voiceLock) {
                long end = System.currentTimeMillis() + 3000;
                while (!voiceBegan && System.currentTimeMillis() < end) {
                    voiceLock.wait(100);
                }
                session = voiceSession;
            }
            if (!voiceBegan) {
                listener.status("a TV não abriu o microfone");
                return;
            }
            send(new Pb().raw(30, new Pb().varint(1, session).done()).done());
            int rate = pickRate();
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            mic = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, VOICE_CHUNK * 4));
            if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
                listener.status("microfone não abriu");
                return;
            }
            mic.startRecording();
            listener.status("fale agora");
            int step = Math.max(1, rate / 8000);
            byte[] raw = new byte[VOICE_CHUNK * step];
            while (wantVoice) {
                int got = 0;
                while (wantVoice && got < raw.length) {
                    int n = mic.read(raw, got, raw.length - got);
                    if (n <= 0) {
                        break;
                    }
                    got += n;
                }
                if (got <= 0) {
                    break;
                }
                send(new Pb().raw(31, new Pb().varint(1, session).raw(2, downsample(raw, got, step)).done()).done());
            }
            send(new Pb().raw(32, new Pb().varint(1, session).done()).done());
            listener.status("conectado");
        } catch (Exception e) {
            Log.i(TAG, "voz", e);
            listener.status(e.getMessage() == null ? "voz falhou" : e.getMessage());
        } finally {
            wantVoice = false;
            if (mic != null) {
                try {
                    mic.stop();
                } catch (Exception ignored) {
                }
                mic.release();
            }
            listener.hearing(false);
        }
    }

    private static int pickRate() {
        int[] rates = {8000, 16000, 48000, 44100};
        for (int rate : rates) {
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min > 0) {
                return rate;
            }
        }
        return 8000;
    }

    private static byte[] downsample(byte[] raw, int got, int step) {
        int samples = got / 2 / step;
        if (samples < 1) {
            samples = 1;
        }
        int size = Math.max(VOICE_CHUNK, samples * 2);
        byte[] pcm = new byte[size];
        int out = Math.min(samples, size / 2);
        for (int i = 0; i < out; i++) {
            int src = i * step * 2;
            if (src + 1 >= got) {
                break;
            }
            pcm[i * 2] = raw[src];
            pcm[i * 2 + 1] = raw[src + 1];
        }
        return pcm;
    }

    private SSLSocket tls(String host, int port) throws Exception {
        loadIdentity();
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[] {new ClientKey(identity)}, new TrustManager[] {new TrustAll()}, null);
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
        socket.connect(new InetSocketAddress(host, port), 8000);
        socket.setTcpNoDelay(true);
        socket.startHandshake();
        return socket;
    }

    private void loadIdentity() throws Exception {
        if (identity != null) {
            return;
        }
        File certFile = new File(dir, "client.cer");
        File keyFile = new File(dir, "client.pk8");
        if (certFile.isFile() && keyFile.isFile()) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) factory.generateCertificate(new FileInputStream(certFile));
            byte[] pkcs8 = readAll(keyFile);
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            identity = new Cert(key, cert);
            return;
        }
        identity = Cert.create("Celular");
        FileOutputStream certOut = new FileOutputStream(certFile);
        certOut.write(identity.cert.getEncoded());
        certOut.close();
        FileOutputStream keyOut = new FileOutputStream(keyFile);
        keyOut.write(identity.key.getEncoded());
        keyOut.close();
    }

    private void send(byte[] message) throws Exception {
        synchronized (writeLock) {
            if (output == null) {
                throw new Exception("sem conexão");
            }
            write(output, message);
        }
    }

    private static void write(OutputStream out, byte[] message) throws Exception {
        out.write(Pb.frame(message));
        out.flush();
    }

    private static byte[] outer(int field, byte[] body) {
        return new Pb().varint(1, 2).varint(2, 200).raw(field, body).done();
    }

    private static byte[] readMessage(InputStream in) throws Exception {
        int length = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new Exception("a TV fechou");
            }
            length |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift > 28) {
                throw new Exception("mensagem inválida");
            }
        }
        if (length < 0 || length > 1024 * 1024) {
            throw new Exception("mensagem grande demais");
        }
        byte[] message = new byte[length];
        int got = 0;
        while (got < length) {
            int n = in.read(message, got, length - got);
            if (n < 0) {
                throw new Exception("a TV fechou");
            }
            got += n;
        }
        return message;
    }

    private String waitPin() throws InterruptedException {
        synchronized (pinLock) {
            pinReady = false;
            pinCode = null;
            while (!pinReady) {
                pinLock.wait();
            }
            return pinCode;
        }
    }

    private void stopReader() {
        runReader = false;
        ready = false;
        SSLSocket current = socket;
        socket = null;
        output = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
            }
        }
        if (reader != null) {
            try {
                reader.join(1500);
            } catch (InterruptedException ignored) {
            }
            reader = null;
        }
    }

    private static byte[] readAll(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        byte[] data = new byte[(int) file.length()];
        int got = 0;
        while (got < data.length) {
            int n = in.read(data, got, data.length - got);
            if (n < 0) {
                break;
            }
            got += n;
        }
        in.close();
        return data;
    }

    private static byte[] hex(String text) {
        int n = text.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
