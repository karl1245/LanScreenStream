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

        // Fallback: try copying to Bitmap when the format is RGBA_8888
        try {
            Bitmap bmp = rgbaImageToBitmap(image);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos);
            bmp.recycle();
            return baos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap rgbaImageToBitmap(Image image) {
        // ImageFormat.RGBA_8888 is exposed as PixelFormat.RGBA_8888 but in Image it's "private" constant.
        // We can still read Plane[0] directly (packed RGBA).
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride   = plane.getRowStride();
        int width  = image.getWidth();
        int height = image.getHeight();

        int rowPadding = rowStride - pixelStride * width;
        Bitmap bmp = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
        );
        bmp.copyPixelsFromBuffer(buf);
        Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, width, height);
        if (cropped != bmp) bmp.recycle();
        return cropped;
    }

    private static byte[] yuv420888ToJpeg(Image image, int jpegQuality) {
        int width  = image.getWidth();
        int height = image.getHeight();

        // Allocate NV21 buffer (Y + interleaved VU)
        byte[] nv21 = new byte[width * height * 3 / 2];

        Image.Plane[] planes = image.getPlanes();
        // ----- Copy Y -----
        ByteBuffer yBuf = planes[0].getBuffer();
        int yRowStride  = planes[0].getRowStride();
        int yPixelStride= planes[0].getPixelStride();
        extractPlaneToArray(yBuf, yRowStride, yPixelStride, width, height, nv21, 0, 1);

        // ----- Copy UV (U & V) → NV21 interleaved (VU order) -----
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int uvRowStride   = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        int chromaWidth  = (int)Math.ceil(width  / 2.0);
        int chromaHeight = (int)Math.ceil(height / 2.0);

        int pos = width * height; // start of UV in NV21
        // Iterate each chroma pixel and interleave V then U (NV21)
        for (int row = 0; row < chromaHeight; row++) {
            int uRowStart = row * uvRowStride;
            int vRowStart = row * planes[2].getRowStride();
            for (int col = 0; col < chromaWidth; col++) {
                int uIndex = uRowStart + col * uvPixelStride;
                int vIndex = vRowStart + col * planes[2].getPixelStride();
                nv21[pos++] = vBuf.get(vIndex); // V
                nv21[pos++] = uBuf.get(uIndex); // U
            }
        }

        // Encode to JPEG
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, width, height), jpegQuality, baos);
        return baos.toByteArray();
    }

    /**
     * Copy a plane into a linear byte[] honoring row/pixel strides.
     * For Y plane we copy into every byte; for UV we read separately above.
     */
    private static void extractPlaneToArray(ByteBuffer buf, int rowStride, int pixelStride,
                                            int width, int height, byte[] out, int outOffset, int outPixelStride) {
        // Save buffer state
        buf.mark();
        int rowLen = Math.min(rowStride, width * pixelStride);
        byte[] row = new byte[rowLen];

        int outPos = outOffset;
        for (int r = 0; r < height; r++) {
            // Read full row from buffer
            int pos = r * rowStride;
            buf.position(pos);
            buf.get(row, 0, rowLen);

            // Copy only the needed pixels (respect pixelStride)
            int colOut = outPos;
            for (int c = 0; c < width; c++) {
                out[colOut] = row[c * pixelStride];
                colOut += outPixelStride;
            }
            outPos += width * outPixelStride;
        }

        buf.reset();
    }
}
