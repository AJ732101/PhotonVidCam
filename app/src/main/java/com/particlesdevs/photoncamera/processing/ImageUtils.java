package com.particlesdevs.photoncamera.processing;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.annotation.RequiresApi;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.ui.camera.views.viewfinder.MainRenderer;
import com.particlesdevs.photoncamera.util.Log;

/**
 * Utility class for Image conversions.
 * Contains methods for 8-bit SDR and 10-bit SDR/HDR data.
 */
public class ImageUtils {

    private static final String TAG = "ImageUtils";

    // Constants for HLG EOTF (Electro-Optical Transfer Function)
    private static final float HLG_A = 0.17883277f;
    private static final float HLG_B = 0.28466892f;
    private static final float HLG_C = 0.55991073f;

    /**
     * Converts a 10-bit YCBCR_P010 (BT.2020 HLG) Image to a high bit-depth RGBA_1010102 Bitmap.
     * @param image The 10-bit YUV Image object with HLG data.
     * @return A Bitmap in RGBA_1010102 format, ready for the encoder.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    public static Bitmap p010HdrToBitmap1010102(Image image) {
        if (image.getFormat() != ImageFormat.YCBCR_P010) {
            throw new IllegalArgumentException("Image must be in YCBCR_P010 format");
        }

        int width = image.getWidth();
        int height = image.getHeight();

        Image.Plane yPlane = image.getPlanes()[0];
        Image.Plane uvPlane = image.getPlanes()[1];

        ByteBuffer yBuffer = yPlane.getBuffer().order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer uvBuffer = uvPlane.getBuffer().order(ByteOrder.LITTLE_ENDIAN);

        int yRowStride = yPlane.getRowStride();
        int uvRowStride = uvPlane.getRowStride();
        int uvPixelStride = uvPlane.getPixelStride();

        int[] pixels = new int[width * height];

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                // Step 1: Read 10-bit Y, U, V values
                int y_10bit = (yBuffer.getShort(j * yRowStride + i * 2) & 0xFFFF) >> 6;
                int uvIndex = (j / 2) * uvRowStride + (i / 2) * uvPixelStride;
                int u_10bit = (uvBuffer.getShort(uvIndex) & 0xFFFF) >> 6;
                int v_10bit = (uvBuffer.getShort(uvIndex + 2) & 0xFFFF) >> 6;

                // Step 2: Convert from limited-range Y'Cb'Cr' to full-range float
                float y_prime = (y_10bit - 64.f) / (940.f - 64.f);
                float cb_prime = (u_10bit - 512.f) / (960.f - 64.f);
                float cr_prime = (v_10bit - 512.f) / (960.f - 64.f);

                // Step 3: Y'Cb'Cr' to R'G'B' conversion with BT.2020 matrix
                float r_prime = y_prime + 1.4746f * cr_prime;
                float g_prime = y_prime - 0.16455f * cb_prime - 0.57135f * cr_prime;
                float b_prime = y_prime + 1.8814f * cb_prime;

                // Step 4: Apply HLG Inverse EOTF to linearize the signal
                float r_linear = hlgToLinear(r_prime);
                float g_linear = hlgToLinear(g_prime);
                float b_linear = hlgToLinear(b_prime);

                // Step 5: Clamp and quantize to 10-bit integer range
                int r_10bit = Math.max(0, Math.min(1023, (int)(r_linear * 1023.0f + 0.5f)));
                int g_10bit = Math.max(0, Math.min(1023, (int)(g_linear * 1023.0f + 0.5f)));
                int b_10bit = Math.max(0, Math.min(1023, (int)(b_linear * 1023.0f + 0.5f)));

                // Step 6: Pack into RGBA_1010102 format (A, B, G, R order)
                int alpha = 3; // Full opacity
                int packed = (alpha << 30) | (b_10bit << 20) | (g_10bit << 10) | r_10bit;
                pixels[j * width + i] = packed;
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGBA_1010102);
    }

    /**
     * Converts a 10-bit YCBCR_P010 (Rec. 709, SDR) Image to a RGBA_1010102 Bitmap.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    public static Bitmap p010SdrToBitmap1010102(Image image) {
        // ... (This method remains the same as in the previous correct answer)
        if (image.getFormat() != ImageFormat.YCBCR_P010) {
            throw new IllegalArgumentException("Image must be in YCBCR_P010 format");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane yPlane = image.getPlanes()[0];
        Image.Plane uvPlane = image.getPlanes()[1];
        ByteBuffer yBuffer = yPlane.getBuffer().order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer uvBuffer = uvPlane.getBuffer().order(ByteOrder.LITTLE_ENDIAN);
        int yRowStride = yPlane.getRowStride();
        int uvRowStride = uvPlane.getRowStride();
        int uvPixelStride = uvPlane.getPixelStride();
        int[] pixels = new int[width * height];
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int y = (yBuffer.getShort(j * yRowStride + i * 2) & 0xFFFF) >> 6;
                int uvIndex = (j / 2) * uvRowStride + (i / 2) * uvPixelStride;
                int u = (uvBuffer.getShort(uvIndex) & 0xFFFF) >> 6;
                int v = (uvBuffer.getShort(uvIndex + 2) & 0xFFFF) >> 6;
                int c = y - 64;
                int d = u - 512;
                int e = v - 512;
                int r = (298 * c + 409 * e + 128) >> 8;
                int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
                int b = (298 * c + 516 * d + 128) >> 8;
                r = Math.max(0, Math.min(1023, r));
                g = Math.max(0, Math.min(1023, g));
                b = Math.max(0, Math.min(1023, b));
                int alpha = 3;
                int packed = (alpha << 30) | (b << 20) | (g << 10) | r;
                pixels[j * width + i] = packed;
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGBA_1010102);
    }

    /**
     * Converts a 10-bit YCBCR_P010 (Rec. 709, SDR) Image to a RGBA_F16 Bitmap.
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static Bitmap p010SdrToBitmapF16(Image image) {
        if (image.getFormat() != ImageFormat.YCBCR_P010) {
            throw new IllegalArgumentException("Image must be in YCBCR_P010 format");
        }

        int width = image.getWidth();
        int height = image.getHeight();

        // Create a Bitmap that can store high-precision float values per channel.
        // This is the format the avif-coder library's JNI layer expects for high bit-depth content.
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16, true);

        Image.Plane yPlane = image.getPlanes()[0];
        Image.Plane uvPlane = image.getPlanes()[1]; // In P010, U and V are interleaved

        ByteBuffer yBuffer = yPlane.getBuffer().order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer uvBuffer = uvPlane.getBuffer().order(ByteOrder.LITTLE_ENDIAN);

        int yRowStride = yPlane.getRowStride();
        int uvRowStride = uvPlane.getRowStride();
        int uvPixelStride = uvPlane.getPixelStride();

        // We will prepare the pixels as a flat float array (R, G, B, A, R, G, B, A, ...)
        float[] rgbaFloats = new float[width * height * 4];

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                // Step 1: Read 10-bit Y, U, V integer values from the buffers.
                // The data is stored in 16-bit shorts, so we read a short and shift away the 6 padding bits.
                int y_10bit = (yBuffer.getShort(j * yRowStride + i * 2) & 0xFFFF) >> 6;
                int uvIndex = (j / 2) * uvRowStride + (i / 2) * uvPixelStride;
                int u_10bit = (uvBuffer.getShort(uvIndex) & 0xFFFF) >> 6;
                int v_10bit = (uvBuffer.getShort(uvIndex + 2) & 0xFFFF) >> 6; // Next short for V

                // Step 2: Convert 10-bit integer Y'CbCr' to normalized float values.
                // This uses the same integer-based math as your 1010102 version for consistency.
                float c = y_10bit - 64.f;
                float d = u_10bit - 512.f;
                float e = v_10bit - 512.f;

                // Step 3: Y'CbCr' to R'G'B' conversion (integer math, then converted to float)
                float r_float = (298.f * c + 409.f * e + 128.f) / 256.f;
                float g_float = (298.f * c - 100.f * d - 208.f * e + 128.f) / 256.f;
                float b_float = (298.f * c + 516.f * d + 128.f) / 256.f;

                // Step 4: Normalize from 10-bit range (0-1023) to float range [0.0, 1.0]
                // and clamp to ensure values are valid.
                float r = Math.max(0.0f, Math.min(1.0f, r_float / 1023.0f));
                float g = Math.max(0.0f, Math.min(1.0f, g_float / 1023.0f));
                float b = Math.max(0.0f, Math.min(1.0f, b_float / 1023.0f));

                // Step 5: Fill the float array for the bitmap buffer.
                int pixelIndex = (j * width + i) * 4;
                rgbaFloats[pixelIndex]     = r;
                rgbaFloats[pixelIndex + 1] = g;
                rgbaFloats[pixelIndex + 2] = b;
                rgbaFloats[pixelIndex + 3] = 1.0f; // Alpha channel
            }
        }

        // Step 6: Efficiently copy the prepared float data into the RGBA_F16 bitmap.
        bitmap.copyPixelsFromBuffer(java.nio.FloatBuffer.wrap(rgbaFloats));

        return bitmap;
    }

    /**
     * Converts a YUV_420_888 (8-bit) Image to a standard ARGB_8888 Bitmap.
     * Uses GPU HardwareBuffer acceleration on Android 9+ when available,
     * and an allocation-free CPU fallback.
     */
    public static Bitmap yuv8BitToBitmap(Image image) {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            throw new IllegalArgumentException("Image must be in YUV_420_888 format");
        }

        if (PhotonCamera.getSettings().alternateImageReaderFlags) {
            return privateBitToBitmap(image);
        }

        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        Image.Plane yPlane = planes[0];
        Image.Plane uPlane = planes[1];
        Image.Plane vPlane = planes[2];

        ByteBuffer yBuffer = yPlane.getBuffer();
        ByteBuffer uBuffer = uPlane.getBuffer();
        ByteBuffer vBuffer = vPlane.getBuffer();

        int yRowStride = yPlane.getRowStride();
        int uvRowStride = uPlane.getRowStride();
        int uvPixelStride = uPlane.getPixelStride();

        int[] argb = new int[width * height];

        for (int j = 0; j < height; j++) {
            int yRowOffset = j * yRowStride;
            int uvRowOffset = (j / 2) * uvRowStride;

            for (int i = 0; i < width; i++) {
                int y = yBuffer.get(yRowOffset + i) & 0xFF;
                int uvIndex = uvRowOffset + (i / 2) * uvPixelStride;

                int u = (uBuffer.get(uvIndex) & 0xFF) - 128;
                int v = (vBuffer.get(uvIndex) & 0xFF) - 128;

                int r = (int) (y + 1.402f * v);
                int g = (int) (y - 0.344f * u - 0.714f * v);
                int b = (int) (y + 1.772f * u);

                r = Math.max(0, Math.min(255, r));
                g = Math.max(0, Math.min(255, g));
                b = Math.max(0, Math.min(255, b));

                argb[j * width + i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bmp.setPixels(argb, 0, width, 0, 0, width, height);
        return bmp;
    }

    /**
     * Resolves the HardwareBuffer integer format to a human-readable string.
     */
    public static String getHardwareBufferFormatName(int format) {
        // Check standard Android HardwareBuffer formats
        switch (format) {
            case HardwareBuffer.RGBA_8888:
                return "RGBA_8888";
            case HardwareBuffer.RGBX_8888:
                return "RGBX_8888";
            case HardwareBuffer.RGB_888:
                return "RGB_888";
            case HardwareBuffer.RGB_565:
                return "RGB_565";
            case HardwareBuffer.BLOB:
                return "BLOB";
            case HardwareBuffer.YCBCR_420_888:
                return "YUV420";
            default:
                // Check for P010 (available from API 33 / Android 13 upwards)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && format == HardwareBuffer.YCBCR_P010) {
                    return "P010";
                }
                if (format == 2141391878) {
                    return "P010 (Vendor Encoded)";
                }
                if (format == 34) {
                    return "??? (Vendor Encoded)";
                }
                if (format == 17) {
                    return "HAL_PIXEL_FORMAT_YCrCb_420_SP (Vendor Encoded)";
                }
                // Fallback for vendor-specific or custom gralloc/HAL formats (e.g., 2141391878)
                return "VENDOR_OR_CUSTOM_FORMAT";
        }
    }

    /**
     * Converts a PRIVATE (8/10-bit) Image to a standard RGBA_F16 Bitmap.
     * Expects an Image originating from an ImageReader created with HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE.
     * Uses HardwareRenderer + BitmapShader to sample the PRIVATE texture on GPU, falling back to Canvas.
     */
    public static Bitmap privateBitToBitmap(Image image) {
        if ((image.getFormat() != ImageFormat.PRIVATE) && (image.getFormat() != ImageFormat.YUV_420_888)) {
            throw new IllegalArgumentException("Image must be in PRIVATE format");
        }

        int width = image.getWidth();
        int height = image.getHeight();

        HardwareBuffer inputBuffer = image.getHardwareBuffer();
        if (inputBuffer == null) {
            throw new IllegalArgumentException("HardwareBuffer is null for PRIVATE Image");
        }

        int format = inputBuffer.getFormat();
        String formatName = getHardwareBufferFormatName(format);
        Log.d(TAG, "PRIVATE Capture Internal Buffer Format: " + format + " (" + formatName + ")");

        Bitmap inputHardwareBitmap = null;
        ImageReader outputReader = null;
        HandlerThread callbackThread = null;
        HardwareRenderer renderer = null;

        try {
            ColorSpace colorSpace = null;
            if (image.getFormat() == ImageFormat.PRIVATE) {
                ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB);
            } else {
                ColorSpace.get(ColorSpace.Named.SRGB);
            }
            inputHardwareBitmap = Bitmap.wrapHardwareBuffer(inputBuffer, colorSpace);
            if (inputHardwareBitmap == null) {
                throw new IllegalStateException("Failed to wrap input HardwareBuffer into Bitmap");
            }

            BitmapShader shader = new BitmapShader(inputHardwareBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);

            Paint paint = new Paint();
            paint.setFilterBitmap(true);
            float gamma = PhotonCamera.getSettings().gammaValue;
            boolean swapRedBlue = image.getFormat() == ImageFormat.YUV_420_888 ? true : false;
            if ((gamma != 1.0f || swapRedBlue) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                String agsl = "uniform shader image;\n" +
                        "uniform float uGamma;\n" +
                        "uniform int uSwapRedBlue;\n" + //
                        "half4 main(float2 fragCoord) {\n" +
                        "    half4 color = image.eval(fragCoord);\n" +
                        "    if (uSwapRedBlue != 0) {\n" +
                        "        color.rgb = color.bgr;\n" +
                        "    }\n" +
                        "    color.rgb = pow(color.rgb, half3(1.0 / uGamma));\n" +
                        "    return color;\n" +
                        "}";

                android.graphics.RuntimeShader runtimeShader = new android.graphics.RuntimeShader(agsl);
                runtimeShader.setInputShader("image", shader);
                runtimeShader.setFloatUniform("uGamma", gamma);
                runtimeShader.setIntUniform("uSwapRedBlue", swapRedBlue ? 1 : 0);

                paint.setShader(runtimeShader);
            } else {
                paint.setShader(shader);
            }

            long usageFlags = HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                    | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                    | HardwareBuffer.USAGE_CPU_READ_OFTEN;

            try {
                if (image.getFormat() == ImageFormat.PRIVATE) {
                    outputReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_F16, 1, usageFlags);
                } else {
                    outputReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1, usageFlags);
                }
            } catch (Exception e1) {
                try {
                    if (image.getFormat() == ImageFormat.PRIVATE) {
                        outputReader = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 1, usageFlags);
                    } else {
                        outputReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 1, usageFlags);
                    }
                } catch (Exception e2) {
                    Log.w(TAG, "ImageReader F16/PRIVATE initialization failed, falling back to Canvas rendering", e2);
                    return renderToCanvas(shader, width, height);
                }
            }

            callbackThread = new HandlerThread("RenderThread");
            callbackThread.start();
            Handler callbackHandler = new Handler(callbackThread.getLooper());

            ConditionVariable frameAvailable = new ConditionVariable();
            outputReader.setOnImageAvailableListener(reader -> frameAvailable.open(), callbackHandler);

            RenderNode node = new RenderNode("PrivateBitToBitmapNode");
            node.setPosition(0, 0, width, height);
            RecordingCanvas recordingCanvas = node.beginRecording(width, height);
            recordingCanvas.drawRect(0, 0, width, height, paint);
            node.endRecording();

            renderer = new HardwareRenderer();
            renderer.setSurface(outputReader.getSurface());
            renderer.setContentRoot(node);
            renderer.setOpaque(false);
            renderer.createRenderRequest().syncAndDraw();

            if (frameAvailable.block(3000)) {
                try (Image resultImage = outputReader.acquireNextImage()) {
                    if (resultImage != null) {
                        try (HardwareBuffer outputHb = resultImage.getHardwareBuffer()) {
                            if (outputHb != null) {
                                Bitmap wrapped = Bitmap.wrapHardwareBuffer(outputHb, colorSpace);
                                if (wrapped != null) {
                                    Bitmap f16Bitmap = null;
                                    if (image.getFormat() == ImageFormat.PRIVATE) {
                                        f16Bitmap = wrapped.copy(Bitmap.Config.RGBA_F16, true);
                                    } else {
                                        f16Bitmap = wrapped.copy(Bitmap.Config.ARGB_8888, true);
                                    }
                                    wrapped.recycle();
                                    return f16Bitmap;
                                }
                            }
                        }
                    }
                }
            } else {
                Log.w(TAG, "HardwareRenderer frame render timed out, falling back to Canvas rendering");
                return renderToCanvas(shader, width, height);
            }
        } catch (Exception e) {
            Log.e(TAG, "Hardware rendering failed, falling back to Canvas rendering: " + e.getMessage(), e);
            if (inputHardwareBitmap != null) {
                BitmapShader shader = new BitmapShader(inputHardwareBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                return renderToCanvas(shader, width, height);
            }
        } finally {
            if (renderer != null) {
                renderer.destroy();
            }
            if (outputReader != null) {
                outputReader.close();
            }
            if (callbackThread != null) {
                callbackThread.quitSafely();
            }
            if (inputHardwareBitmap != null) {
                inputHardwareBitmap.recycle();
            }
            inputBuffer.close();
        }

        return null;
    }

    private static Bitmap renderToCanvas(Shader shader, int width, int height) {
        try {
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint();
            paint.setShader(shader);
            paint.setFilterBitmap(true);
            canvas.drawRect(0, 0, width, height, paint);
            return bmp;
        } catch (Exception e) {
            Log.e(TAG, "Canvas fallback rendering failed: " + e.getMessage(), e);
            return null;
        }
    }

    private static float hlgToLinear(float hlgValue) {
        hlgValue = Math.max(0.0f, hlgValue);
        if (hlgValue <= 0.5f) {
            return (hlgValue * hlgValue) / 3.0f;
        } else {
            return ((float)Math.exp((hlgValue - HLG_C) / HLG_A) + HLG_B) / 12.0f;
        }
    }

    /**
     * Converts a 10-bit YCBCR_P010 Image to an RGBA_F16 Bitmap using OpenGL shaders for performance.
     * This method is significantly faster than CPU-based conversion.
     *
     * @param image The 10-bit YUV Image object.
     * @param renderer The active MainRenderer instance to execute GL commands.
     * @return A Bitmap in RGBA_F16 format.
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static Bitmap p010SdrToF16BitmapGL(Image image, MainRenderer renderer) throws ExecutionException, InterruptedException {
        if (renderer == null) {
            throw new IllegalArgumentException("MainRenderer instance cannot be null for GL-based conversion.");
        }
        // Delegate the complex GL task to the renderer and wait for the result.
        Future<Bitmap> futureBitmap = renderer.processP010SdrImageGL(image);
        // This will block until the GL thread has finished processing and returned the bitmap.
        return futureBitmap.get();
    }

}
