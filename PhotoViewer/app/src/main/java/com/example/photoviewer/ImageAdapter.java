package com.example.photoviewer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public final class ImageAdapter extends RecyclerView.Adapter<ImageAdapter.Holder> {
    public interface MemoListener { void open(PostItem post); }
    private final List<PostItem> items;
    private final MemoListener memoListener;
    public ImageAdapter(List<PostItem> items, MemoListener memoListener) {
        this.items = items;
        this.memoListener = memoListener;
    }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_image, parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        PostItem post = items.get(position);
        holder.image.setImageBitmap(post.bitmap);
        holder.image.setContentDescription(post.title + " 게시물 사진");
        holder.title.setText(post.title);
        holder.body.setText(post.text);
        holder.date.setText(post.createdDate);
        String text = post.ocrError != null ? "사진 글자 인식 실패: " + post.ocrError
                : post.ocrText == null ? "사진 글자: 아직 인식하지 않았습니다."
                : post.ocrText.trim().isEmpty() ? "사진 글자: 인식된 글자가 없습니다."
                : "사진 속 글자:\n" + post.ocrText;
        holder.ocr.setText(text);
        holder.memo.setOnClickListener(view -> memoListener.open(post));
        holder.image.setOnClickListener(view -> memoListener.open(post));
    }
    @Override public int getItemCount() { return items.size(); }
    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView title, body, date, ocr;
        final Button memo;
        Holder(View view) {
            super(view);
            image = view.findViewById(R.id.imageViewItem);
            title = view.findViewById(R.id.postTitle);
            body = view.findViewById(R.id.postBody);
            date = view.findViewById(R.id.postDate);
            ocr = view.findViewById(R.id.postOcr);
            memo = view.findViewById(R.id.btnMemo);
        }
    }
}
