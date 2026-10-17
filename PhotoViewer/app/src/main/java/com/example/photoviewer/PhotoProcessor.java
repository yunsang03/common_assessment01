package com.example.photoviewer;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Bounded decoding, orientation correction and measured JPEG compression. */
public final class PhotoProcessor {
    private PhotoProcessor() { }
    private static final int MAX_EDGE = 2400;
    private static final int MAX_INPUT_BYTES = 32 * 1024 * 1024;

    public static final class Photo {
        public final Bitmap original;
        public final long sourceSize;
        public final int sourceWidth, sourceHeight;
        public Bitmap editorBitmap, previewBitmap;
        public byte[] uploadBytes;
        public String mime, filename;
        public boolean redacted;
        public int revision;

        Photo(Bitmap bitmap, byte[] bytes, String mime, int width, int height) {
            original = bitmap;
            editorBitmap = bitmap;
            previewBitmap = bitmap;
            uploadBytes = bytes;
            sourceSize = bytes.length;
            sourceWidth = width;
            sourceHeight = height;
            this.mime = mime;
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            filename = "android_upload." + (ext == null ? "img" : ext);
        }
    }

    public static Photo load(ContentResolver resolver, Uri uri) throws IOException {
        byte[] bytes;
        try (InputStream input = resolver.openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("사진을 열 수 없습니다.");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_INPUT_BYTES)
                    throw new IOException("편집할 사진은 32MB 이하로 선택해주세요.");
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw new IOException("지원하는 이미지 파일을 선택해주세요.");
        Bitmap bitmap = decodeBounded(bytes);
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            ExifInterface exif = new ExifInterface(input);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case 2: matrix.setScale(-1, 1); break;
                case 3: matrix.setRotate(180); break;
                case 4: matrix.setScale(1, -1); break;
                case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                case 6: matrix.setRotate(90); break;
                case 7: matrix.setRotate(-90); matrix.postScale(-1, 1); break;
                case 8: matrix.setRotate(-90); break;
                default: break;
            }
            if (!matrix.isIdentity()) {
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                        bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotated != bitmap) bitmap.recycle();
                bitmap = rotated;
            }
        } catch (IOException ignored) {
            // Some supported formats have no EXIF segment.
        }
        String mime = resolver.getType(uri);
        if (mime == null || !mime.startsWith("image/")) mime = bounds.outMimeType;
        if (mime == null) mime = "image/jpeg";
        return new Photo(bitmap, bytes, mime, bounds.outWidth, bounds.outHeight);
    }

    public static Bitmap decodeBounded(byte[] bytes) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        int longEdge = Math.max(options.outWidth, options.outHeight);
        options.inSampleSize = 1;
        while (longEdge / options.inSampleSize > MAX_EDGE)
            options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IOException("이미지 디코딩에 실패했습니다.");
        return bitmap;
    }

    public static byte[] jpeg(Bitmap bitmap, int quality) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                throw new IOException("이미지 변환에 실패했습니다.");
            return output.toByteArray();
        }
    }

    public static Bitmap opaqueCopy(Bitmap source) {
        Bitmap result = Bitmap.createBitmap(source.getWidth(), source.getHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(source, 0, 0, null);
        return result;
    }

    public static final class Compressed {
        public final byte[] bytes;
        public final Bitmap bitmap;
        public final int quality;
        Compressed(byte[] bytes, Bitmap bitmap, int quality) {
            this.bytes = bytes;
            this.bitmap = bitmap;
            this.quality = quality;
        }
    }

    public static Compressed compress(Bitmap source, int targetBytes) throws IOException {
        Bitmap current = opaqueCopy(source);
        for (int round = 0; round < 18; round++) {
            int low = 30, high = 95, bestQuality = -1;
            byte[] best = null;
            while (low <= high) {
                int quality = (low + high) / 2;
                byte[] bytes = jpeg(current, quality);
                if (bytes.length <= targetBytes) {
                    best = bytes;
                    bestQuality = quality;
                    low = quality + 1;
                } else high = quality - 1;
            }
            if (best != null) {
                // Preview the decoded, compressed bytes, not the uncompressed bitmap.
                Bitmap preview = BitmapFactory.decodeByteArray(best, 0, best.length);
                current.recycle();
                if (preview == null) throw new IOException("변환 결과를 열 수 없습니다.");
                return new Compressed(best, preview, bestQuality);
            }
            int width = Math.max(1, (int) (current.getWidth() * 0.8f));
            int height = Math.max(1, (int) (current.getHeight() * 0.8f));
            if (width == current.getWidth() && height == current.getHeight()) break;
            Bitmap smaller = Bitmap.createScaledBitmap(current, width, height, true);
            if (smaller != current) current.recycle();
            current = smaller;
        }
        current.recycle();
        throw new IOException("목표 용량에 맞출 수 없습니다. 더 큰 목표를 선택해주세요.");
    }
}
