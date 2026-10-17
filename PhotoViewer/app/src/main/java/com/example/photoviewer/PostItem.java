package com.example.photoviewer;

import android.graphics.Bitmap;

/** One server post, including a device-local OCR index. */
public final class PostItem {
    public final String title, text, imageUrl, createdDate;
    public final Bitmap bitmap;
    public String ocrText;
    public String ocrError;

    public PostItem(String title, String text, String imageUrl,
                    String createdDate, Bitmap bitmap) {
        this.title = title;
        this.text = text;
        this.imageUrl = imageUrl;
        this.createdDate = createdDate;
        this.bitmap = bitmap;
    }
}
