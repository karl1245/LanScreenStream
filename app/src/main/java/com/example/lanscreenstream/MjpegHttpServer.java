package com.example.lanscreenstream;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MjpegHttpServer {

    private static final String TAG = "MjpegHttpServer";

    public interface FrameSource {
        byte[] getLatestJpeg();
    }

    private final int port;
    private final int maxClients;
    private volatile boolean running = false;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService clientPool;

    private FrameSource frameSource;

    // Simple viewer page with "Save Screenshot" button
    private static final String INDEX_HTML =
            "<!DOCTYPE html><html><head><meta charset='utf-8'/>"
                    + "<meta name='viewport' content='width=device-width,initial-scale=1'/>"
                    + "<title>LAN Screen Stream</title>"
                    + "<style>body{font-family:system-ui,Arial;margin:16px} "
                    + "#v{max-width:100%;border:1px solid #ccc;border-radius:8px}</style>"
                    + "</head><body>"
                    + "<h2>LAN Screen Stream</h2>"
                    + "<p>Live MJPEG stream:</p>"
                    + "<img id='v' src='/stream.mjpg' alt='stream'/>"
                    + "<div style='margin-top:12px;'>"
                    + "  <button id='btnShot'>Save Screenshot</button>"
                    + "  <a id='dl' href='/frame.jpg' download style='margin-left:8px'>Download current frame</a>"
                    + "</div>"
                    + "<script>"
                    + "document.getElementById('btnShot').onclick=()=>{"
                    + "  fetch('/frame.jpg',{cache:'no-store'}).then(r=>r.blob()).then(b=>{"
                    + "    const url=URL.createObjectURL(b);"
                    + "    const a=document.createElement('a');"
                    + "    const ts=new Date().toISOString().replace(/[:.]/g,'-');"
                    + "    a.href=url; a.download='screenshot-'+ts+'.jpg';"
                    + "    document.body.appendChild(a); a.click(); a.remove();"
                    + "    URL.revokeObjectURL(url);"
                    + "  }).catch(e=>alert('Failed to save screenshot: '+e));"
                    + "};"
                    + "</script>"
                    + "</body></html>";

    public MjpegHttpServer(int port, int maxClients) {
        this.port = port;
        this.maxClients = Math.max(1, maxClients);
    }

    public void setFrameSource(FrameSource src) {
        this.frameSource = src;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        running = true;

        serverSocket = new ServerSocket(port);
        clientPool = Executors.newFixedThreadPool(maxClients);

        acceptThread = new Thread(() -> {
            Log.d(TAG, "Accept thread started on port " + port);
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    clientPool.submit(() -> handleClient(client));
                } catch (IOException e) {
                    if (running) {
                        Log.e(TAG, "Accept failed", e);
                    }
                }
            }
            Log.d(TAG, "Accept thread exiting");
        }, "mjpeg-accept");
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        closeQuietly(serverSocket);
        serverSocket = null;

        if (acceptThread != null) {
            try { acceptThread.join(500); } catch (InterruptedException ignored) {}
            acceptThread = null;
        }
        if (clientPool != null) {
            clientPool.shutdownNow();
            clientPool = null;
        }
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket;
             BufferedInputStream in = new BufferedInputStream(s.getInputStream());
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
             OutputStream rawOut = new BufferedOutputStream(s.getOutputStream())) {

            s.setSoTimeout(0); // keep alive for stream

            // Parse request line
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0];
            String path = parts[1];

            // Consume headers (we don’t need them here)
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // no-op
            }

            if (!"GET".equalsIgnoreCase(method)) {
                sendHttpResponse(rawOut, "405 Method Not Allowed", "text/plain; charset=UTF-8",
                        "Only GET supported".getBytes(StandardCharsets.UTF_8));
                return;
            }

            // Route
            switch (normalizePath(path)) {
                case "/":
                case "/index.html":
                    sendHttpResponse(rawOut, "200 OK", "text/html; charset=UTF-8",
                            INDEX_HTML.getBytes(StandardCharsets.UTF_8));
                    return;

                case "/frame.jpg": {
                    byte[] frame = frameSource != null ? frameSource.getLatestJpeg() : null;
                    if (frame == null) {
                        sendHttpResponse(rawOut, "503 Service Unavailable", "text/plain; charset=UTF-8",
                                "No frame".getBytes(StandardCharsets.UTF_8));
                    } else {
                        sendHttpResponse(rawOut, "200 OK", "image/jpeg", frame);
                    }
                    return;
                }

                case "/stream.mjpg":
                case "/stream.mjpeg":
                case "/stream": {
                    streamMultipart(rawOut);
                    return;
                }

                default:
                    sendHttpResponse(rawOut, "404 Not Found", "text/plain; charset=UTF-8",
                            "Not Found".getBytes(StandardCharsets.UTF_8));
            }

        } catch (IOException e) {
            Log.w(TAG, "Client connection error: " + e.getMessage());
        }
    }

    private void streamMultipart(OutputStream out) throws IOException {
        final String boundary = "frame";
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.US_ASCII), false);

        // Headers
        pw.print("HTTP/1.1 200 OK\r\n");
        pw.print("Connection: close\r\n");
        pw.print("Cache-Control: no-store\r\n");
        pw.print("Pragma: no-cache\r\n");
        pw.print("Content-Type: multipart/x-mixed-replace; boundary=" + boundary + "\r\n");
        pw.print("\r\n");
        pw.flush();

        // Stream loop
        final long minFrameIntervalMs = 10; // ~100 fps ceiling; actual rate depends on producer
        long lastSent = 0;

        while (running) {
            byte[] frame = frameSource != null ? frameSource.getLatestJpeg() : null;
            if (frame == null) {
                sleepQuiet(50);
                continue;
            }

            long now = System.currentTimeMillis();
            if (now - lastSent < minFrameIntervalMs) {
                sleepQuiet(5);
                continue;
            }
            lastSent = now;

            pw.print("--" + boundary + "\r\n");
            pw.print("Content-Type: image/jpeg\r\n");
            pw.print("Content-Length: " + frame.length + "\r\n");
            pw.print("\r\n");
            pw.flush();

            out.write(frame);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Small sleep to avoid hot loop if producer is super fast
            // (MJPEG is pull-like; throttle a bit)
            sleepQuiet(5);
        }

        // Write closing boundary (some clients don’t require this)
        try {
            pw.print("--" + boundary + "--\r\n");
            pw.flush();
        } catch (Throwable ignored) {}
    }

    private void sendHttpResponse(OutputStream out, String status, String contentType, byte[] body) throws IOException {
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.US_ASCII), false);
        pw.print("HTTP/1.1 " + status + "\r\n");
        pw.print("Content-Type: " + contentType + "\r\n");
        pw.print("Content-Length: " + body.length + "\r\n");
        pw.print("Cache-Control: no-store\r\n");
        pw.print("Connection: close\r\n");
        pw.print("\r\n");
        pw.flush();
        out.write(body);
        out.flush();
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (!path.startsWith("/")) path = "/" + path;
        return path.toLowerCase(Locale.US);
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) {}
    }
}
