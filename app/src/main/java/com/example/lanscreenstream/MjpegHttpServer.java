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

    // Viewer page with Fullscreen + Fit/Cover + Save
    private static final String INDEX_HTML =
            "<!doctype html><html><head><meta charset='utf-8'/>"
                    + "<meta name='viewport' content='width=device-width, initial-scale=1, viewport-fit=cover'/>"
                    + "<meta name='mobile-web-app-capable' content='yes'/>"
                    + "<meta name='apple-mobile-web-app-capable' content='yes'/>"
                    + "<meta name='apple-mobile-web-app-status-bar-style' content='black-translucent'/>"
                    + "<title>LAN Screen Stream</title>"
                    + "<style>"
                    + "html,body{height:100%;margin:0;background:#111;color:#eee;font-family:system-ui,Arial}"
                    + /* use dynamic viewport height on mobile */
                    ".wrap{display:flex;flex-direction:column;height:100dvh;height:100svh;height:100vh}"
                    + ".bar{display:flex;gap:.5rem;align-items:center;padding:.6rem 1rem;background:#1b1b1b;box-shadow:0 1px 0 #0008}"
                    + ".btn{padding:.5rem .85rem;border:none;border-radius:.5rem;background:#2c2c2c;color:#eee;cursor:pointer}"
                    + ".btn:active{transform:scale(.98)}"
                    + ".view{flex:1;display:flex;align-items:center;justify-content:center;overflow:hidden;-webkit-user-select:none;user-select:none}"
                    + ".view img{max-width:100%;max-height:100%;object-fit:contain;touch-action:none}"
                    + ".cover img{object-fit:cover;width:100%;height:100%}"
                    + ".hint{opacity:.65;font-size:.9rem;margin:.25rem 1rem 0}"
                    + "a{color:#9bd}"
                    + "</style></head><body>"
                    + "<div class='wrap'>"
                    + "  <div class='bar'>"
                    + "    <button id='fs' class='btn'>Fullscreen</button>"
                    + "    <button id='fit' class='btn'>Fit</button>"
                    + "    <button id='cover' class='btn'>Cover</button>"
                    + "    <button id='btnShot' class='btn'>Save Screenshot</button>"
                    + "    <a id='dl' href='/frame.jpg' download>Download frame</a>"
                    + "  </div>"
                    + "  <div id='view' class='view'><img id='v' src='/stream.mjpg' alt='stream'/></div>"
                    + "</div>"
                    + "<script>"
                    + "const view=document.getElementById('view');"
                    + "const fsBtn=document.getElementById('fs');"
                    + "function enterFS(el){"
                    + "  (el.requestFullscreen||el.webkitRequestFullscreen||el.mozRequestFullScreen||el.msRequestFullscreen)?.call(el);"
                    + "}"
                    + "function exitFS(){"
                    + "  (document.exitFullscreen||document.webkitExitFullscreen||document.mozCancelFullScreen||document.msExitFullscreen)?.call(document);"
                    + "}"
                    + "fsBtn.onclick=()=>{ if(!document.fullscreenElement && !document.webkitFullscreenElement){enterFS(view);} else {exitFS();} };"
                    + "document.getElementById('fit').onclick=()=>{view.classList.remove('cover');};"
                    + "document.getElementById('cover').onclick=()=>{view.classList.add('cover');};"
                    + "document.addEventListener('keydown',e=>{ if(e.key==='f') fsBtn.click(); });"
                    + "view.addEventListener('dblclick',()=>fsBtn.click());"
                    + "// Keep the screen awake where supported (Chrome/Android and most modern browsers)"
                    + "let wakeLock=null;"
                    + "async function requestWakeLock(){"
                    + "  try{ wakeLock=await navigator.wakeLock?.request('screen');"
                    + "       wakeLock?.addEventListener('release',()=>{}); }catch(e){}"
                    + "}"
                    + "requestWakeLock();"
                    + "document.addEventListener('visibilitychange',()=>{ if(document.visibilityState==='visible' && wakeLock?.released) requestWakeLock(); });"
                    + "// Mobile viewport fix for older iOS (fallback to JS-calculated vh)"
                    + "(function(){"
                    + "  const setVH=()=>{ document.documentElement.style.setProperty('--vh', (window.innerHeight*0.01)+'px'); };"
                    + "  setVH(); window.addEventListener('resize', setVH);"
                    + "})();"
                    + "</script></body></html>";

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

            // Consume headers
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) { /* ignore */ }

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
        pw.print("Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n");
        pw.print("Pragma: no-cache\r\n");
        pw.print("Content-Type: multipart/x-mixed-replace; boundary=" + boundary + "\r\n");
        pw.print("\r\n");
        pw.flush();

        // Stream loop
        final long minFrameIntervalMs = 66; // ~15 fps pacing
        long lastSent = 0;

        while (running) {
            byte[] frame = frameSource != null ? frameSource.getLatestJpeg() : null;
            if (frame == null) {
                sleepQuiet(20);
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
        }

        // Closing boundary (optional for many clients)
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
