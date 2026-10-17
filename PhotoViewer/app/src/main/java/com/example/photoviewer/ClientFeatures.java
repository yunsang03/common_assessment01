package com.example.photoviewer;

import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Five independent client conveniences; no change to the Django schema is required. */
public final class ClientFeatures {
    private final MainActivity activity;
    private final ExecutorService executor;
    private final SharedPreferences preferences;
    private final TextRecognizer recognizer = TextRecognition.getClient(
            new KoreanTextRecognizerOptions.Builder().build());
    private final List<PostItem> posts = new ArrayList<>();
    private final List<Dialog> dialogs = new ArrayList<>();
    private RecyclerView recycler;
    private EditText query;
    private CheckBox imageOnly;
    private Button scan;
    private TextView status;
    private View form;
    private boolean busy;
    private volatile boolean closed;
    private int composerGeneration;
    public PhotoProcessor.Photo selectedPhoto;

    public ClientFeatures(MainActivity activity, ExecutorService executor) {
        this.activity = activity;
        this.executor = executor;
        preferences = activity.getSharedPreferences("client_features_v1", 0);
    }
    public boolean isBusy() { return busy; }
    private boolean alive() { return !closed && !activity.isDestroyed(); }
    private void message(String text) {
        if (alive()) Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }
    public void attachMain() {
        recycler = activity.findViewById(R.id.recyclerView);
        query = activity.findViewById(R.id.editSearch);
        imageOnly = activity.findViewById(R.id.checkImageOnly);
        scan = activity.findViewById(R.id.btnScanText);
        status = activity.findViewById(R.id.textView);
        scan.setOnClickListener(view -> scanPosts());
        imageOnly.setOnCheckedChangeListener((button, checked) -> refreshResults());
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshResults();
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        recycler.setAdapter(new ImageAdapter(new ArrayList<>(), this::openMemos));
        status.setText("동기화를 눌러 게시물을 불러오세요.");
    }
    public void refreshControls(boolean enabled) {
        scan.setEnabled(enabled && !posts.isEmpty());
        query.setEnabled(enabled);
        imageOnly.setEnabled(enabled);
    }
    public void setPosts(List<PostItem> items) {
        posts.clear();
        posts.addAll(items);
        refreshResults();
    }
    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
    private void refreshResults() {
        if (!alive()) return;
        String term = normalized(query.getText().toString());
        boolean only = imageOnly.isChecked();
        List<PostItem> matches = new ArrayList<>();
        int indexed = 0;
        for (PostItem post : posts) {
            if (post.ocrText != null) indexed++;
            boolean match = term.isEmpty()
                    || normalized(post.ocrText == null ? "" : post.ocrText).contains(term)
                    || (!only && (normalized(post.title).contains(term)
                    || normalized(post.text).contains(term)));
            if (match) matches.add(post);
        }
        recycler.setAdapter(new ImageAdapter(matches, this::openMemos));
        if (!busy) status.setText("동기화 " + posts.size() + "개 · 표시 " + matches.size()
                + "개\n사진 글자 인식 " + indexed + "/" + posts.size() + "개"
                + (term.isEmpty() ? "" : " · 검색: " + query.getText()));
    }
    private static String key(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte part : digest) result.append(String.format(Locale.ROOT, "%02x", part & 255));
            return result.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    public void restoreOcr(PostItem post) {
        post.ocrText = preferences.getString("ocr_" + key(post.imageUrl), null);
    }
    private void setBusy(boolean value, String text) {
        busy = value;
        activity.refreshButtons();
        if (activity.isComposerOpen()) {
            activity.setUploadBusy(value);
            ((TextView) form.findViewById(R.id.uploadStatus)).setText(text);
        }
        if (!text.isEmpty()) status.setText(text);
    }
    private void scanPosts() {
        if (busy || activity.isTransferBusy() || posts.isEmpty()) return;
        setBusy(true, "사진 속 글자를 인식하고 있습니다.");
        List<PostItem> snapshot = new ArrayList<>(posts);
        executor.execute(() -> {
            int completed = 0, failed = 0;
            for (PostItem post : snapshot) {
                if (closed || Thread.currentThread().isInterrupted()) return;
                // Recognize again so replacing image bytes at the same URL can be refreshed.
                try {
                    Text result = Tasks.await(recognizer.process(
                            InputImage.fromBitmap(post.bitmap, 0)), 60, TimeUnit.SECONDS);
                    String text = result.getText();
                    preferences.edit().putString("ocr_" + key(post.imageUrl), text).apply();
                    activity.runOnUiThread(() -> {
                        if (!alive()) return;
                        post.ocrText = text;
                        post.ocrError = null;
                    });
                } catch (Exception error) {
                    if (error instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    failed++;
                    activity.runOnUiThread(() -> {
                        if (!alive()) return;
                        post.ocrText = null;
                        post.ocrError = "사진을 확인한 후 다시 인식해주세요.";
                        preferences.edit().remove("ocr_" + key(post.imageUrl)).apply();
                    });
                }
                completed++;
                final int progress = completed;
                activity.runOnUiThread(() -> {
                    if (alive()) status.setText("사진 글자 인식 " + progress + "/" + snapshot.size());
                });
            }
            final int failures = failed;
            activity.runOnUiThread(() -> {
                if (!alive()) return;
                setBusy(false, "");
                refreshResults();
                message("사진 글자 인식 완료 · 실패 " + failures + "개");
            });
        });
    }
    public void attachComposer(View composer) {
        composerGeneration++;
        form = composer;
        selectedPhoto = null;
        composer.findViewById(R.id.btnPrivacy).setOnClickListener(view -> scanPrivacy());
        composer.findViewById(R.id.btnOptimize).setOnClickListener(view -> chooseTarget());
        composer.findViewById(R.id.btnCompare).setOnClickListener(view -> {
            if (requirePhoto()) showCompare(selectedPhoto, null, null);
        });
    }
    private boolean composerValid(int generation) {
        return alive() && generation == composerGeneration && activity.isComposerOpen();
    }
    public void loadPhoto(Uri uri, String source) {
        if (busy || !activity.isComposerOpen()) return;
        final int generation = composerGeneration;
        selectedPhoto = null;
        ((android.widget.ImageView) form.findViewById(R.id.imagePreview)).setImageDrawable(null);
        setBusy(true, "사진을 준비하고 있습니다.");
        executor.execute(() -> {
            try {
                PhotoProcessor.Photo photo = PhotoProcessor.load(activity.getContentResolver(), uri);
                activity.runOnUiThread(() -> {
                    if (!composerValid(generation)) return;
                    selectedPhoto = photo;
                    ((TextView) form.findViewById(R.id.shareStatus)).setText("사진 입력: " + source);
                    setBusy(false, "사진 준비 완료. 필요하면 가림 처리와 용량 최적화를 적용하세요.");
                    refreshPhoto();
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (!composerValid(generation)) return;
                    setBusy(false, "사진을 열 수 없습니다: " + error.getMessage());
                });
            }
        });
    }
    @SuppressWarnings("deprecation")
    public void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        if (intent.getType() == null || !intent.getType().startsWith("image/")) return;
        Uri uri;
        if (Build.VERSION.SDK_INT >= 33) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        else uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null || !"content".equals(uri.getScheme())) {
            message("다른 앱에서 이미지 파일을 공유해주세요.");
            intent.setAction(null);
            return;
        }
        if (busy || activity.isTransferBusy()) {
            message("현재 작업이 끝난 후 사진을 다시 공유해주세요.");
            intent.setAction(null);
            return;
        }
        Runnable open = () -> {
            for (Dialog dialog : new ArrayList<>(dialogs))
                if (dialog.isShowing()) dialog.dismiss();
            activity.onClickUpload(null);
            if (!activity.isComposerOpen()) return;
            String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (subject != null) ((EditText) form.findViewById(R.id.editTitle)).setText(subject);
            if (text != null) ((EditText) form.findViewById(R.id.editText)).setText(text);
            intent.setAction(null);
            loadPhoto(uri, "다른 앱에서 공유받음");
        };
        if (activity.isComposerOpen()) {
            new AlertDialog.Builder(activity).setTitle("공유받은 사진으로 새로 작성")
                    .setMessage("현재 작성 화면을 닫고 공유받은 사진으로 새 글을 작성할까요?")
                    .setNegativeButton("취소", (dialog, which) -> intent.setAction(null))
                    .setPositiveButton("새로 작성", (dialog, which) -> {
                        activity.dismissComposer();
                        open.run();
                    }).show();
        } else open.run();
    }
    private boolean requirePhoto() {
        if (busy) return false;
        if (selectedPhoto == null) { message("사진을 먼저 선택해주세요."); return false; }
        return true;
    }
    private String size(long bytes) {
        return String.format(Locale.KOREA, "%,d 바이트 (%.1f KB)", bytes, bytes / 1024.0);
    }
    private void refreshPhoto() {
        PhotoProcessor.Photo photo = selectedPhoto;
        if (photo == null || !activity.isComposerOpen()) return;
        ((android.widget.ImageView) form.findViewById(R.id.imagePreview))
                .setImageBitmap(photo.previewBitmap);
        ((TextView) form.findViewById(R.id.imageStatus)).setText(
                "원본: " + size(photo.sourceSize) + "\n현재 게시 파일: " + size(photo.uploadBytes.length)
                + "\n" + (photo.redacted ? "가림 처리 적용됨 · " : "") + photo.mime);
    }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setBackgroundColor(Color.WHITE);
        return view;
    }
    private TextView label(String value, int sp) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(Color.rgb(25, 35, 50));
        text.setPadding(0, dp(6), 0, dp(6));
        return text;
    }
    private Button button(String value, Runnable action) {
        Button button = new Button(activity);
        button.setText(value);
        button.setOnClickListener(view -> action.run());
        return button;
    }
    private void row(LinearLayout parent, Button... buttons) {
        LinearLayout row = new LinearLayout(activity);
        for (Button button : buttons)
            row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(row);
    }
    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
    private Dialog fullScreen(LinearLayout content) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        dialogs.add(dialog);
        dialog.setOnDismissListener(ignored -> dialogs.remove(dialog));
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.white);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(dp(12) + bars.left, dp(8) + bars.top,
                    dp(12) + bars.right, dp(8) + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
        return dialog;
    }
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Z0-9._%+\\-]+\\s*@\\s*[A-Z0-9.\\-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile(
            "(?:\\+82[ .\\-]?)?0\\d{1,2}[ .\\-]?\\d{3,4}[ .\\-]?\\d{4}");

    private void scanPrivacy() {
        if (!requirePhoto()) return;
        PhotoProcessor.Photo photo = selectedPhoto;
        int generation = composerGeneration;
        setBusy(true, "가릴 연락처 후보를 찾고 있습니다.");
        executor.execute(() -> {
            List<PhotoMarkupView.MaskRegion> candidates = new ArrayList<>();
            boolean success = true;
            try {
                Text result = Tasks.await(recognizer.process(
                        InputImage.fromBitmap(photo.editorBitmap, 0)), 60, TimeUnit.SECONDS);
                for (Text.TextBlock block : result.getTextBlocks()) {
                    for (Text.Line line : block.getLines()) {
                        String content = line.getText();
                        if (!EMAIL.matcher(content).find() && !PHONE.matcher(content).find()) continue;
                        Rect box = line.getBoundingBox();
                        if (box == null) continue;
                        float width = photo.editorBitmap.getWidth(), height = photo.editorBitmap.getHeight();
                        RectF normalized = new RectF(Math.max(0, (box.left - 6) / width),
                                Math.max(0, (box.top - 6) / height),
                                Math.min(1, (box.right + 6) / width),
                                Math.min(1, (box.bottom + 6) / height));
                        candidates.add(new PhotoMarkupView.MaskRegion(normalized, false));
                    }
                }
            } catch (Exception error) {
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
                success = false;
            }
            final boolean recognized = success;
            activity.runOnUiThread(() -> {
                if (!composerValid(generation) || selectedPhoto != photo) return;
                setBusy(false, "가릴 영역을 확인해주세요.");
                openPrivacyEditor(photo, candidates, recognized);
            });
        });
    }
    private void openPrivacyEditor(PhotoProcessor.Photo photo,
            List<PhotoMarkupView.MaskRegion> candidates, boolean recognized) {
        LinearLayout root = column();
        root.addView(label("사진 속 개인정보 가리기", 21));
        root.addView(label((recognized ? "연락처 후보 " + candidates.size() + "곳. "
                : "자동 인식에 실패했습니다. 직접 가릴 수 있습니다. ")
                + "빨간 테두리를 누르면 검게 가려집니다. 빠진 부분은 직접 지정하세요.", 14));
        PhotoMarkupView canvas = new PhotoMarkupView(activity, photo.editorBitmap);
        canvas.masks.addAll(candidates);
        root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView summary = label("선택 0곳 · 두 손가락 확대 / 두 번 눌러 확대", 14);
        root.addView(summary);
        canvas.masksChanged = () -> summary.setText("선택 " + canvas.selectedCount() + "곳");
        Button manual = button("직접 지정", () -> { });
        manual.setOnClickListener(view -> {
            canvas.setDrawMode(!canvas.isDrawMode());
            manual.setText(canvas.isDrawMode() ? "선택/이동 모드" : "직접 지정");
            summary.setText(canvas.isDrawMode() ? "손가락으로 사각형을 그리세요. 두 손가락 확대 가능."
                    : "후보를 눌러 선택/해제하세요. 선택 " + canvas.selectedCount() + "곳");
        });
        row(root, button("후보 전체 선택", () -> {
            for (PhotoMarkupView.MaskRegion region : canvas.masks) region.selected = true;
            canvas.invalidate();
            canvas.masksChanged.run();
        }), manual);
        row(root, button("전체 해제", () -> {
            for (PhotoMarkupView.MaskRegion region : canvas.masks) region.selected = false;
            canvas.invalidate();
            canvas.masksChanged.run();
        }));
        Dialog dialog = fullScreen(root);
        row(root, button("취소", dialog::dismiss), button("선택 영역 가리기", () -> {
            if (selectedPhoto != photo || !activity.isComposerOpen()) { dialog.dismiss(); return; }
            if (canvas.selectedCount() == 0) { message("가릴 영역을 선택해주세요."); return; }
            int generation = composerGeneration;
            List<RectF> regions = new ArrayList<>();
            for (PhotoMarkupView.MaskRegion region : canvas.masks)
                if (region.selected) regions.add(new RectF(region.bounds));
            dialog.dismiss();
            setBusy(true, "가림 처리된 이미지 파일을 만들고 있습니다.");
            executor.execute(() -> {
                try {
                    Bitmap edited = PhotoProcessor.opaqueCopy(photo.editorBitmap);
                    Canvas painter = new Canvas(edited);
                    Paint black = new Paint();
                    black.setColor(Color.BLACK);
                    for (RectF region : regions) painter.drawRect(
                            (float) Math.floor(region.left * edited.getWidth()),
                            (float) Math.floor(region.top * edited.getHeight()),
                            (float) Math.ceil(region.right * edited.getWidth()),
                            (float) Math.ceil(region.bottom * edited.getHeight()), black);
                    byte[] bytes = PhotoProcessor.jpeg(edited, 95);
                    activity.runOnUiThread(() -> {
                        if (!composerValid(generation) || selectedPhoto != photo) return;
                        photo.editorBitmap = edited;
                        photo.previewBitmap = edited;
                        photo.uploadBytes = bytes;
                        photo.mime = "image/jpeg";
                        photo.filename = "android_redacted.jpg";
                        photo.redacted = true;
                        photo.revision++;
                        setBusy(false, "가림 처리 적용 완료. 최종 미리보기에서 확인해주세요."
                                + " 이전 용량 최적화 결과는 해제되었습니다.");
                        refreshPhoto();
                    });
                } catch (Exception error) {
                    activity.runOnUiThread(() -> {
                        if (composerValid(generation)) setBusy(false, "가림 처리 실패: " + error.getMessage());
                    });
                }
            });
        }));
    }
    private void chooseTarget() {
        if (!requirePhoto()) return;
        String[] labels = {"256 KB 이하", "512 KB 이하", "1 MB 이하"};
        int[] limits = {256 * 1024, 512 * 1024, 1024 * 1024};
        new AlertDialog.Builder(activity).setTitle("게시 사진 목표 용량")
                .setItems(labels, (dialog, which) -> compressPhoto(limits[which]))
                .setNegativeButton("취소", null).show();
    }
    private void compressPhoto(int target) {
        if (!requirePhoto()) return;
        PhotoProcessor.Photo photo = selectedPhoto;
        if (photo.uploadBytes.length <= target) {
            message("현재 파일이 이미 목표 용량 이하입니다: " + size(photo.uploadBytes.length));
            return;
        }
        int generation = composerGeneration, revision = photo.revision;
        setBusy(true, "목표 용량에 맞춰 변환하고 있습니다.");
        executor.execute(() -> {
            try {
                PhotoProcessor.Compressed result = PhotoProcessor.compress(photo.editorBitmap, target);
                activity.runOnUiThread(() -> {
                    if (!composerValid(generation) || selectedPhoto != photo || photo.revision != revision) return;
                    setBusy(false, "변환 결과를 확인하고 적용해주세요.");
                    showCompare(photo, result, () -> {
                        if (selectedPhoto != photo || !composerValid(generation)) return;
                        photo.previewBitmap = result.bitmap;
                        photo.uploadBytes = result.bytes;
                        photo.mime = "image/jpeg";
                        photo.filename = photo.redacted ? "android_redacted_optimized.jpg" : "android_optimized.jpg";
                        photo.revision++;
                        refreshPhoto();
                        ((TextView) form.findViewById(R.id.uploadStatus)).setText(
                                "목표 " + size(target) + " 이하 · 실제 " + size(result.bytes.length) + " 적용 완료");
                    });
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (composerValid(generation)) setBusy(false, "용량 최적화 실패: " + error.getMessage());
                });
            }
        });
    }
    private void showCompare(PhotoProcessor.Photo photo, PhotoProcessor.Compressed proposed, Runnable apply) {
        LinearLayout root = column();
        root.addView(label(proposed == null ? "최종 게시 사진 확인" : "용량 최적화 결과 확인", 21));
        byte[] bytes = proposed == null ? photo.uploadBytes : proposed.bytes;
        Bitmap after = proposed == null ? photo.previewBitmap : proposed.bitmap;
        double saving = (1 - (double) bytes.length / photo.sourceSize) * 100;
        root.addView(label("원본 " + size(photo.sourceSize) + " · " + photo.sourceWidth + "×" + photo.sourceHeight
                + "\n결과 " + size(bytes.length) + " · 미리보기 " + after.getWidth() + "×" + after.getHeight()
                + "\n" + (saving >= 0 ? "용량 절감 " : "용량 증가 ")
                + String.format(Locale.KOREA, "%.1f%%", Math.abs(saving))
                + (proposed == null ? "" : " · JPEG 품질 " + proposed.quality), 14));
        PhotoMarkupView preview = new PhotoMarkupView(activity, after);
        TextView previewLabel = label("결과 사진 · 두 손가락 또는 두 번 눌러 확대", 14);
        root.addView(previewLabel);
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        final boolean[] showOriginal = {false};
        final PhotoMarkupView[] current = {preview};
        Button toggle = button("원본 보기", () -> { });
        toggle.setOnClickListener(view -> {
            int index = root.indexOfChild(current[0]);
            root.removeView(current[0]);
            showOriginal[0] = !showOriginal[0];
            current[0] = new PhotoMarkupView(activity, showOriginal[0] ? photo.original : after);
            root.addView(current[0], index, new LinearLayout.LayoutParams(-1, 0, 1));
            toggle.setText(showOriginal[0] ? "결과 사진 보기" : "원본 보기");
            previewLabel.setText(showOriginal[0] ? "원본 사진 (편집용 미리보기)"
                    : "결과 사진 · 두 손가락 또는 두 번 눌러 확대");
        });
        root.addView(toggle);
        root.addView(label("가림 영역과 작은 글자를 확인하세요. 원본 파일은 수정하지 않습니다.", 13));
        Dialog dialog = fullScreen(root);
        if (apply == null) root.addView(button("확인", dialog::dismiss));
        else row(root, button("적용 안 함", dialog::dismiss), button("이 결과 적용", () -> {
            apply.run();
            dialog.dismiss();
        }));
    }
    private void openMemos(PostItem post) {
        if (busy || activity.isTransferBusy()) { message("현재 작업이 끝난 후 열어주세요."); return; }
        LinearLayout root = column();
        root.addView(label("사진 위치 메모 · " + post.title, 20));
        root.addView(label("사진을 눌러 메모 추가 · 번호를 눌러 수정/삭제\n두 손가락 확대 / 확대 후 이동 / 두 번 눌러 확대", 14));
        PhotoMarkupView canvas = new PhotoMarkupView(activity, post.bitmap);
        String storageKey = "memos_" + key(post.imageUrl);
        try {
            JSONArray saved = new JSONArray(preferences.getString(storageKey, "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject pin = saved.getJSONObject(i);
                canvas.pins.add(new PhotoMarkupView.Pin((float) pin.getDouble("x"),
                        (float) pin.getDouble("y"), pin.getString("text")));
            }
        } catch (Exception error) { message("저장된 메모를 읽지 못했습니다."); }
        root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView savedStatus = label("이 기기에 저장된 메모 " + canvas.pins.size() + "개", 14);
        root.addView(savedStatus);
        Runnable persist = () -> {
            try {
                JSONArray saved = new JSONArray();
                for (PhotoMarkupView.Pin pin : canvas.pins)
                    saved.put(new JSONObject().put("x", pin.x).put("y", pin.y).put("text", pin.text));
                preferences.edit().putString(storageKey, saved.toString()).apply();
                savedStatus.setText("이 기기에 저장 완료 · 메모 " + canvas.pins.size() + "개");
                canvas.invalidate();
            } catch (Exception error) { message("메모를 저장하지 못했습니다."); }
        };
        canvas.tapListener = (x, y) -> editMemo(canvas, canvas.hitPin(x, y), x, y, persist);
        root.addView(button("메모 목록 보기", () -> {
            if (canvas.pins.isEmpty()) { message("사진을 눌러 메모를 추가해주세요."); return; }
            String[] items = new String[canvas.pins.size()];
            for (int i = 0; i < items.length; i++) items[i] = (i + 1) + ". " + canvas.pins.get(i).text;
            new AlertDialog.Builder(activity).setTitle("사진 위치 메모")
                    .setItems(items, (dialog, which) -> {
                        PhotoMarkupView.Pin pin = canvas.pins.get(which);
                        editMemo(canvas, which, pin.x, pin.y, persist);
                    }).setNegativeButton("닫기", null).show();
        }));
        root.addView(label("개인 메모는 기기에 저장됩니다. 서버 사진과 다른 사용자의 화면에는 반영되지 않습니다.", 13));
        Dialog dialog = fullScreen(root);
        root.addView(button("닫기", dialog::dismiss));
    }
    private void editMemo(PhotoMarkupView canvas, int index, float x, float y, Runnable persist) {
        EditText input = new EditText(activity);
        input.setTextColor(Color.rgb(25, 35, 50));
        input.setHintTextColor(Color.GRAY);
        input.setHint("이 위치에 대한 설명을 입력하세요");
        input.setMinLines(2);
        input.setMaxLines(6);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        if (index >= 0) input.setText(canvas.pins.get(index).text);
        LinearLayout content = column();
        content.addView(input);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(index >= 0 ? "위치 메모 " + (index + 1) : "새 위치 메모")
                .setView(content).setNegativeButton("취소", null).setPositiveButton("저장", null);
        if (index >= 0) builder.setNeutralButton("삭제", (dialog, which) -> {
            canvas.pins.remove(index);
            persist.run();
        });
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) { input.setError("메모를 입력해주세요."); return; }
            if (index >= 0) canvas.pins.get(index).text = text;
            else canvas.pins.add(new PhotoMarkupView.Pin(x, y, text));
            persist.run();
            dialog.dismiss();
        }));
        dialog.show();
    }
    public void close() {
        closed = true;
        for (Dialog dialog : new ArrayList<>(dialogs)) if (dialog.isShowing()) dialog.dismiss();
        recognizer.close();
    }
}
