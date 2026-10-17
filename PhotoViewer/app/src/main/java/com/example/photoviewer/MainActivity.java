package com.example.photoviewer;

import android.content.pm.PackageManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String LOCAL_PERMISSION =
            "android.permission.ACCESS_LOCAL_NETWORK";

    private final String site_url = "http://10.0.2.2:8000";
    private final String token = "PUT_YOUR_TOKEN_HERE";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView textView;
    private Button btnLoad;
    private Button btnSave;
    private RecyclerView recyclerView;
    private boolean downloading = false;
    private boolean uploading = false;
    private Runnable pendingNetworkAction;

    private AlertDialog uploadDialog;
    private View uploadForm;
    private ClientFeatures features;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(), granted -> {
                        Runnable action = pendingNetworkAction;
                        pendingNetworkAction = null;
                        if (granted && action != null) {
                            action.run();
                        } else if (!granted) {
                            Toast.makeText(this,
                                    "로컬 서버에 연결하려면 로컬 네트워크 접근을 허용해주세요.",
                                    Toast.LENGTH_LONG).show();
                        }
                    });

    private final ActivityResultLauncher<String[]> imagePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null || uploadDialog == null || !uploadDialog.isShowing()) {
                    return;
                }
                features.loadPhoto(uri, "파일에서 사진 선택");
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main),
                (view, windowInsets) -> {
                    Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return windowInsets;
                });

        textView = findViewById(R.id.textView);
        btnLoad = findViewById(R.id.btn_load);
        btnSave = findViewById(R.id.btn_save);
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        features = new ClientFeatures(this, executor);
        features.attachMain();
        btnLoad.setOnClickListener(this::onClickDownload);
        btnSave.setOnClickListener(this::onClickUpload);
        refreshButtons();
        findViewById(R.id.main).post(() -> features.handleShare(getIntent()));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        features.handleShare(intent);
    }

    boolean isTransferBusy() { return uploading || downloading; }
    boolean isComposerOpen() { return uploadDialog != null && uploadDialog.isShowing(); }
    void dismissComposer() { if (uploadDialog != null) uploadDialog.dismiss(); }

    private boolean tokenReady() {
        if (token.equals("PUT_YOUR_TOKEN_HERE") || token.trim().isEmpty()) {
            Toast.makeText(this, "서버 인증 토큰을 먼저 설정해주세요.",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private void withNetworkPermission(Runnable action) {
        boolean localServer = site_url.startsWith("http://10.0.2.2:");
        if (Build.VERSION.SDK_INT >= 37 && localServer
                && ContextCompat.checkSelfPermission(this, LOCAL_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            pendingNetworkAction = action;
            permissionLauncher.launch(LOCAL_PERMISSION);
        } else {
            action.run();
        }
    }

    void refreshButtons() {
        boolean enabled = !downloading && !uploading && (features == null || !features.isBusy());
        btnLoad.setEnabled(enabled);
        btnSave.setEnabled(enabled);
        if (features != null) features.refreshControls(enabled);
    }

    public void onClickDownload(View view) {
        if (!downloading && !uploading && !features.isBusy() && tokenReady()) {
            withNetworkPermission(this::downloadImages);
        }
    }

    private void downloadImages() {
        if (downloading || uploading || isDestroyed()) {
            return;
        }
        downloading = true;
        refreshButtons();
        textView.setText("사진을 불러오는 중입니다.");

        executor.execute(() -> {
            try {
                List<PostItem> images = fetchImages();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    features.setPosts(images);
                    downloading = false;
                    refreshButtons();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    textView.setText("동기화 실패: " + e.getMessage());
                    downloading = false;
                    refreshButtons();
                });
            }
        });
    }

    public void onClickUpload(View view) {
        if (downloading || uploading || features.isBusy() || !tokenReady()) return;

        uploadForm = getLayoutInflater().inflate(R.layout.dialog_post, null);
        EditText author = uploadForm.findViewById(R.id.editAuthor);
        EditText created = uploadForm.findViewById(R.id.editCreated);
        EditText published = uploadForm.findViewById(R.id.editPublished);
        author.setText("1");

        SimpleDateFormat format =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        String now = format.format(new Date());
        created.setText(now);
        published.setText(now);

        Button selectImage = uploadForm.findViewById(R.id.btnSelectImage);
        selectImage.setOnClickListener(v -> imagePicker.launch(new String[]{"image/*"}));

        uploadDialog = new AlertDialog.Builder(this)
                .setTitle("새로운 이미지 게시")
                .setView(uploadForm)
                .setNegativeButton("취소", null)
                .setPositiveButton("게시", null)
                .create();
        uploadDialog.setOnShowListener(dialog -> uploadDialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> submitPost()));
        uploadDialog.show();
        features.attachComposer(uploadForm);
    }

    private void submitPost() {
        if (uploading || features.isBusy() || uploadDialog == null || !uploadDialog.isShowing()) return;

        EditText author = uploadForm.findViewById(R.id.editAuthor);
        EditText title = uploadForm.findViewById(R.id.editTitle);
        EditText text = uploadForm.findViewById(R.id.editText);
        EditText created = uploadForm.findViewById(R.id.editCreated);
        EditText published = uploadForm.findViewById(R.id.editPublished);

        String authorValue = author.getText().toString().trim();
        try {
            if (Integer.parseInt(authorValue) <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            author.setError("유효한 작성자 ID를 입력해주세요.");
            return;
        }
        if (title.getText().toString().trim().isEmpty()) {
            title.setError("제목을 입력해주세요.");
            return;
        }
        if (text.getText().toString().trim().isEmpty()) {
            text.setError("내용을 입력해주세요.");
            return;
        }
        if (created.getText().toString().trim().isEmpty()) {
            created.setError("작성 날짜를 입력해주세요.");
            return;
        }
        if (published.getText().toString().trim().isEmpty()) {
            published.setError("게시 날짜를 입력해주세요.");
            return;
        }
        if (features.selectedPhoto == null) {
            Toast.makeText(this, "사진을 선택해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("author", authorValue);
        fields.put("title", title.getText().toString().trim());
        fields.put("text", text.getText().toString().trim());
        fields.put("created_date", created.getText().toString().trim());
        fields.put("published_date", published.getText().toString().trim());
        PhotoProcessor.Photo image = features.selectedPhoto;
        if (image.uploadBytes.length > 10 * 1024 * 1024) {
            Toast.makeText(this, "게시할 사진이 10MB를 넘습니다. 용량 최적화를 먼저 적용해주세요.", Toast.LENGTH_LONG).show();
            return;
        }

        withNetworkPermission(() -> {
            if (uploadDialog != null && uploadDialog.isShowing()) {
                uploadPost(fields, image);
            }
        });
    }

    void setUploadBusy(boolean busy) {
        uploadDialog.setCancelable(!busy);
        uploadDialog.setCanceledOnTouchOutside(!busy);
        uploadDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
        uploadDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!busy);
        int[] ids = {R.id.editAuthor, R.id.editTitle, R.id.editText,
                R.id.editCreated, R.id.editPublished, R.id.btnSelectImage,
                R.id.btnPrivacy, R.id.btnOptimize, R.id.btnCompare};
        for (int id : ids) uploadForm.findViewById(id).setEnabled(!busy);
        TextView status = uploadForm.findViewById(R.id.uploadStatus);
        status.setText(busy ? "게시 중입니다. 잠시 기다려주세요." : "");
    }

    private void uploadPost(Map<String, String> fields, PhotoProcessor.Photo image) {
        if (uploading || downloading || isDestroyed()) return;
        uploading = true;
        refreshButtons();
        setUploadBusy(true);

        executor.execute(() -> {
            try {
                JSONObject result = postMultipart(fields, image);
                String postedTitle = result.getString("title");
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    uploading = false;
                    refreshButtons();
                    if (uploadDialog != null) uploadDialog.dismiss();
                    textView.setText("게시 완료: " + postedTitle
                            + "\n동기화를 눌러 사진을 확인하세요.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    uploading = false;
                    refreshButtons();
                    if (uploadDialog != null && uploadDialog.isShowing()) {
                        setUploadBusy(false);
                        TextView status = uploadForm.findViewById(R.id.uploadStatus);
                        status.setText("게시 실패: " + e.getMessage());
                    }
                });
            }
        });
    }

    private JSONObject postMultipart(Map<String, String> fields, PhotoProcessor.Photo image)
            throws Exception {
        String mime = image.mime;
        String filename = image.filename;
        byte[] imageBytes = image.uploadBytes;
        String boundary = "PhotoViewer" + UUID.randomUUID().toString().replace("-", "");

        byte[] body;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (Map.Entry<String, String> field : fields.entrySet()) {
                writeUtf8(output, "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\""
                        + field.getKey() + "\"\r\n"
                        + "Content-Type: text/plain; charset=utf-8\r\n\r\n"
                        + field.getValue() + "\r\n");
            }
            writeUtf8(output, "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"image\"; filename=\""
                    + filename + "\"\r\n"
                    + "Content-Type: " + mime + "\r\n\r\n");
            output.write(imageBytes);
            writeUtf8(output, "\r\n--" + boundary + "--\r\n");
            body = output.toByteArray();
        }

        HttpURLConnection connection = (HttpURLConnection)
                new URL(site_url + "/api_root/Post/").openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Authorization", "Token " + token.trim());
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + boundary);
            connection.setFixedLengthStreamingMode(body.length);

            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            String response = readText(stream);
            if (status != HttpURLConnection.HTTP_CREATED) {
                throw new IOException("HTTP " + status + " "
                        + response.substring(0, Math.min(response.length(), 600)));
            }
            return new JSONObject(response);
        } finally {
            connection.disconnect();
        }
    }

    private static void writeUtf8(OutputStream output, String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String readText(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
        }
        return text.toString();
    }

    private List<PostItem> fetchImages() throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL(site_url + "/api_root/Post/").openConnection();
        String json;
        try {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Token " + token.trim());
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("API HTTP " + status);
            }
            json = readText(connection.getInputStream());
        } finally {
            connection.disconnect();
        }

        JSONArray posts = new JSONArray(json);
        List<PostItem> images = new ArrayList<>();
        for (int i = 0; i < posts.length(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("동기화가 중단되었습니다.");
            }
            JSONObject item = posts.getJSONObject(i);
            String imagePath = item.optString("image", "");
            if (!imagePath.isEmpty() && !imagePath.equals("null")) {
                URL imageUrl = new URL(new URL(site_url), imagePath);
                PostItem post = new PostItem(item.optString("title", ""),
                        item.optString("text", ""), imageUrl.toString(),
                        item.optString("created_date", ""), fetchBitmap(imageUrl));
                features.restoreOcr(post);
                images.add(post);
            }
        }
        return images;
    }

    private Bitmap fetchBitmap(URL imageUrl) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) imageUrl.openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("사진 HTTP " + status);
            }
            try (InputStream stream = connection.getInputStream()) {
                try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (bytes.size() + count > 32 * 1024 * 1024)
                            throw new IOException("동기화할 사진은 한 장당 32MB 이하여야 합니다.");
                        bytes.write(buffer, 0, count);
                    }
                    return PhotoProcessor.decodeBounded(bytes.toByteArray());
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    @Override
    protected void onDestroy() {
        pendingNetworkAction = null;
        if (features != null) features.close();
        if (uploadDialog != null) uploadDialog.dismiss();
        executor.shutdownNow();
        super.onDestroy();
    }
}
