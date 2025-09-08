package com.example.lanscreenstream;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MjpegHttpServer {

    public interface FrameProvider {
        byte[] getLatestJpeg();
    }

    private static final String TAG = "MjpegHttpServer";
    private static final String BOUNDARY = "--mjpegframe";
    private final int port;
    private final FrameProvider provider;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public MjpegHttpServer(int port, FrameProvider provider) {
        this.port = port;
        this.provider = provider;
    }

    public void start() throws IOException {
        if (running) return;
        running = true;
        serverSocket = new ServerSocket(port);
        pool.execute(() -> {
            while (running) {
                try {
                    Socket s = serverSocket.accept();
                    pool.execute(() -> serveClient(s));
                } catch (IOException ignored) {
                    if (!running) break;
                }
            }
        });
    }

    public void stopServer() {
        running = false;
        closeQuietly(serverSocket);
        pool.shutdownNow();
    }

    private void serveClient(Socket socket) {
        try (Socket s = socket;
             OutputStream raw = new BufferedOutputStream(s.getOutputStream())) {

            writeHeaders(raw);

            while (running && !s.isClosed()) {
                byte[] jpeg = provider.getLatestJpeg();
                if (jpeg == null) {
                    sleepQuiet(20);
                    continue;
                }

                String partHeader = "\r\n--" + BOUNDARY + "\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: " + jpeg.length + "\r\n\r\n";
                raw.write(partHeader.getBytes(StandardCharsets.US_ASCII));
                raw.write(jpeg);
                raw.flush();

                // ~15 fps pacing to avoid saturating LAN
                sleepQuiet(66);
            }
        } catch (IOException ignored) {
        }
    }

    private void writeHeaders(OutputStream out) throws IOException {
        String headers = "HTTP/1.1 200 OK\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n" +
                "Pragma: no-cache\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) {}
    }
}
