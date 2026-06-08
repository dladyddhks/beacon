package com.example.moduflow;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.util.Base64;

import androidx.camera.core.ImageProxy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class ImageUtils {

    /**
     * ImageProxy를 Base64 JPEG 문자열로 변환합니다.
     * 
     * @param imageProxy CameraX에서 제공하는 이미지 프레임
     * @param targetWidth 변환할 이미지의 가로 크기 (세로는 비율에 맞춰 자동 조절)
     * @param quality JPEG 압축 품질 (0~100)
     * @return Base64 인코딩된 JPEG 문자열
     */
    public static String toBase64Jpeg(ImageProxy imageProxy, int targetWidth, int quality) {
        Bitmap bitmap = imageProxyToBitmap(imageProxy);
        if (bitmap == null) return null;

        // 1. 센서 방향에 따른 회전 처리
        if (imageProxy.getImageInfo().getRotationDegrees() != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(imageProxy.getImageInfo().getRotationDegrees());
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        }

        // 2. 가로/세로 비율 유지하며 다운스케일링
        float scale = (float) targetWidth / bitmap.getWidth();
        int targetHeight = (int) (bitmap.getHeight() * scale);
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);

        // 3. JPEG 압축
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
        byte[] imageBytes = baos.toByteArray();

        // 4. Base64 인코딩 (No Wrap 옵션 사용)
        return Base64.encodeToString(imageBytes, Base64.NO_WRAP);
    }

    /**
     * CameraX ImageProxy(YUV_420_888)를 Bitmap으로 변환한다.
     *
     * CameraX가 제공하는 포맷은 YUV_420_888이지만 Android의 YuvImage는
     * NV21(Y평면 + VU 인터리브)만 지원한다. 따라서 plane 순서를 Y→V→U로
     * 재배열하여 NV21 바이트 배열을 만든 뒤 YuvImage로 JPEG 변환한다.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private static Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        Image image = imageProxy.getImage();
        if (image == null) return null;

        ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer(); // Y 평면
        ByteBuffer uBuffer = planes[1].getBuffer(); // U 평면 (Cb)
        ByteBuffer vBuffer = planes[2].getBuffer(); // V 평면 (Cr)

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, imageProxy.getWidth(), imageProxy.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 100, out);
        byte[] imageBytes = out.toByteArray();

        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }
}
