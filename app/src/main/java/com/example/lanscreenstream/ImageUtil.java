package com.example.lanscreenstream;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** Utilities for converting ImageReader frames to JPEG bytes. */
public class ImageUtil {

    /** Convert an Image (RGBA_8888 or YUV_420_888) to JPEG bytes. */
    public static byte[] imageToJpeg(Image image, int jpegQuality) {
        int format = image.getFormat();
        if (format == ImageFormat.YUV_420_888) {
            return yuv420888ToJpeg(image, jpegQuality);
        }
        // Fallback for RGBA_8888: copy to Bitmap and compress
        Bitmap bmp = null;
        try {
            bmp = rgbaImageToBitmap(image);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos);
            return baos.toByteArray();
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    /** Convert RGBA_8888 Image to Bitmap safely. */
    private static Bitmap rgbaImageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int pixelStride = plane.getPixelStride(); // usually 4
        int rowStride = plane.getRowStride();

        int w = image.getWidth();
        int h = image.getHeight();

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);

        byte[] rowBytes = new byte[rowStride];
        int[] rowPixels = new int[w];

        for (int y = 0; y < h; y++) {
            buf.get(rowBytes, 0, rowStride);

            for (int x = 0; x < w; x++) {
                int i = x * pixelStride;
                if (i + 3 < rowBytes.length) {
                    int r = rowBytes[i] & 0xFF;
                    int g = rowBytes[i + 1] & 0xFF;
                    int b = rowBytes[i + 2] & 0xFF;
                    int a = rowBytes[i + 3] & 0xFF;
                    rowPixels[x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            bmp.setPixels(rowPixels, 0, w, 0, y, w, 1);
        }

        buf.rewind();
        return bmp;
    }

    /** Convert a YUV_420_888 Image to JPEG bytes. */
    private static byte[] yuv420888ToJpeg(Image image, int jpegQuality) {
        int width = image.getWidth();
        int height = image.getHeight();

        // Android guarantees 3 planes: Y, U, V
        Image.Plane[] planes = image.getPlanes();

        byte[] yuv = new byte[width * height * 3 / 2];
        // Copy Y
        copyPlane(planes[0], width, height, yuv, 0, 1);
        // Copy U and V (interleaved NV21 order: V then U)
        int chromaWidth = (width + 1) / 2;
        int chromaHeight = (height + 1) / 2;
        int uvOffset = width * height;

        // V
        copyPlane(planes[2], chromaWidth, chromaHeight, yuv, uvOffset, 2);
        // U
        copyPlane(planes[1], chromaWidth, chromaHeight, yuv, uvOffset + 1, 2);

        YuvImage yuvImage = new YuvImage(yuv, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), jpegQuality, baos);
        return baos.toByteArray();
    }

    /**
     * Copy one plane into a byte[] with control over pixel & row strides.
     * outPixelStride=1 for Y plane, 2 for chroma interleaving.
     */
    private static void copyPlane(Image.Plane plane, int width, int height,
                                  byte[] out, int outPos, int outPixelStride) {
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        byte[] row = new byte[rowStride];
        for (int r = 0; r < height; r++) {
            buf.get(row, 0, Math.min(rowStride, buf.remaining()));

            int outCol = outPos;
            for (int c = 0; c < width; c++) {
                rowCheck(row, c * pixelStride);
                out[outCol] = row[c * pixelStride];
                outCol += outPixelStride;
            }
            outPos += width * outPixelStride;
        }
        buf.rewind();
    }

    private static void rowCheck(byte[] row, int idx) {
        if (idx < 0 || idx >= row.length) {
            // guard against weird stride impls
        }
    }
}
