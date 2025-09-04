package com.example.lanscreenstream;

import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MjpegHttpServer extends NanoHTTPD {

    public interface FrameSource {
        byte[] getLatestJpeg(); // may return null if no frame yet
    }

    private volatile FrameSource frameSource;
    private final int fps;

    public MjpegHttpServer(int port, int fps) {
        super(port);
        this.fps = Math.max(1, Math.min(fps, 30));
    }

    public void setFrameSource(FrameSource src) {
        this.frameSource = src;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if ("/".equals(uri)) {
            String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                    + "<title>LAN Screen Stream</title>"
                    + "<style>body{margin:0;background:#111;display:flex;align-items:center;justify-content:center;height:100vh}"
                    + "img{max-width:100vw;max-height:100vh}</style></head>"
                    + "<body><img src='/stream.mjpg' alt='stream'></body></html>";
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
        } else if ("/stream.mjpg".equals(uri)) {
            String boundary = "--frame";
            InputStream is = new MultipartMjpegStream(boundary, () -> frameSource != null ? frameSource.getLatestJpeg() : null, fps);
            Response r = newChunkedResponse(Response.Status.OK,
                    "multipart/x-mixed-replace; boundary=" + boundary, is);
            r.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            r.addHeader("Pragma", "no-cache");
            r.addHeader("Connection", "close");
            return r;
        } else {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        }
    }

    private static class MultipartMjpegStream extends InputStream {
        interface Supplier { byte[] get(); }

        private final String boundary;
        private final Supplier supplier;
        private final long frameDelayMs;

        private byte[] currentChunk;
        private int idx = 0;

        MultipartMjpegStream(String boundary, Supplier supplier, int fps) {
            this.boundary = boundary;
            this.supplier = supplier;
            this.frameDelayMs = 1000L / Math.max(1, fps);
            buildNextChunk(); // try first
        }

        private void buildNextChunk() {
            try {
                byte[] jpeg = supplier.get();
                if (jpeg == null) {
                    Thread.sleep(frameDelayMs);
                    return;
                }
                String header =
                        boundary + "\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: " + jpeg.length + "\r\n\r\n";
                byte[] head = header.getBytes(StandardCharsets.US_ASCII);
                byte[] tail = "\r\n".getBytes(StandardCharsets.US_ASCII);
                currentChunk = new byte[head.length + jpeg.length + tail.length];
                System.arraycopy(head, 0, currentChunk, 0, head.length);
                System.arraycopy(jpeg, 0, currentChunk, head.length, jpeg.length);
                System.arraycopy(tail, 0, currentChunk, head.length + jpeg.length, tail.length);
                idx = 0;
            } catch (InterruptedException ignored) {}
        }

        @Override
        public int read() throws IOException {
            if (currentChunk == null) {
                buildNextChunk();
                return -1;
            }
            if (idx >= currentChunk.length) {
                try { Thread.sleep(frameDelayMs); } catch (InterruptedException ignored) {}
                buildNextChunk();
                if (currentChunk == null) return -1;
            }
            return currentChunk[idx++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (currentChunk == null) {
                buildNextChunk();
                return 0;
            }
            if (idx >= currentChunk.length) {
                try { Thread.sleep(frameDelayMs); } catch (InterruptedException ignored) {}
                buildNextChunk();
                if (currentChunk == null) return 0;
            }
            int toCopy = Math.min(len, currentChunk.length - idx);
            System.arraycopy(currentChunk, idx, b, off, toCopy);
            idx += toCopy;
            return toCopy;
        }
    }
}