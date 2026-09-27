package net.kdt.pojavlaunch.fragments;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.MineButton;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.customcontrols.mouse.Touchpad;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * "Skins & Cape" screen.
 *
 * Skin/cape URLs (whether pasted or produced by an upload) are written to a small JSON config
 * file inside the game directory. This file is meant to be read by the separate NP Skin
 * Minecraft mod, which is the piece that actually applies the skin in-game and auto-runs the
 * skin-restore command on servers that support it. This launcher screen's job is only to let the
 * player choose a skin/cape and hand off a real, publicly fetchable URL (needed for the
 * multiplayer restore command to work, since the server itself has to be able to download it).
 *
 * Cursor selection is different: it's a launcher-only visual (the on-screen touch pointer), so it
 * is applied immediately by writing a preference that {@link Touchpad} reads.
 */
public class SkinCapeFragment extends Fragment {
    public static final String TAG = "SkinCapeFragment";

    // If this repository is ever renamed/moved, update this so preset capes/cursors keep
    // resolving to a real public URL (this matters for capes: a Minecraft server needs to be
    // able to download the URL itself when the skin-restore command runs).
    private static final String REPO_RAW_BASE =
            "https://raw.githubusercontent.com/npsushant25-create/NP-luncher-/v3_openjdk/app_pojavlauncher/src/main/res/drawable/";

    private static final String CONFIG_DIR_NAME = "config";
    private static final String CONFIG_FILE_NAME = "npskin.json";

    private EditText mSkinUrlInput;
    private EditText mCapeUrlInput;
    private ImageView mSkinPreview;
    private ImageView mCapePreview;

    private enum UploadTarget { SKIN, CAPE, CURSOR }
    private UploadTarget mPendingUploadTarget;

    private final ActivityResultLauncher<String> mImagePicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onImagePicked);

    public SkinCapeFragment() {
        super(R.layout.fragment_skin_cape);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mSkinUrlInput = view.findViewById(R.id.skincape_skin_url);
        mCapeUrlInput = view.findViewById(R.id.skincape_cape_url);
        mSkinPreview = view.findViewById(R.id.skincape_skin_preview);
        mCapePreview = view.findViewById(R.id.skincape_cape_preview);

        LinearLayout capePresetContainer = view.findViewById(R.id.skincape_cape_preset_container);
        LinearLayout cursorPresetContainer = view.findViewById(R.id.skincape_cursor_preset_container);

        MineButton skinUploadButton = view.findViewById(R.id.skincape_skin_upload_button);
        MineButton capeUploadButton = view.findViewById(R.id.skincape_cape_upload_button);
        MineButton cursorUploadButton = view.findViewById(R.id.skincape_cursor_upload_button);
        MineButton saveButton = view.findViewById(R.id.skincape_save_button);

        skinUploadButton.setOnClickListener(v -> {
            mPendingUploadTarget = UploadTarget.SKIN;
            mImagePicker.launch("image/*");
        });
        capeUploadButton.setOnClickListener(v -> {
            mPendingUploadTarget = UploadTarget.CAPE;
            mImagePicker.launch("image/*");
        });
        cursorUploadButton.setOnClickListener(v -> {
            mPendingUploadTarget = UploadTarget.CURSOR;
            mImagePicker.launch("image/*");
        });
        saveButton.setOnClickListener(v -> saveConfig());

        buildCapePresets(capePresetContainer);
        buildCursorPresets(cursorPresetContainer);
        loadExistingConfig();
    }

    // ---------------------------------------------------------------------------------------
    // Preset rows
    // ---------------------------------------------------------------------------------------

    private void buildCapePresets(LinearLayout container) {
        addPresetThumb(container, R.drawable.ic_preset_none, () -> {
            mCapeUrlInput.setText("");
            mCapePreview.setVisibility(View.GONE);
        });
        addCapePresetThumb(container, R.drawable.cape_preset_crimson, "cape_preset_crimson.png");
        addCapePresetThumb(container, R.drawable.cape_preset_emerald, "cape_preset_emerald.png");
        addCapePresetThumb(container, R.drawable.cape_preset_obsidian, "cape_preset_obsidian.png");
        addCapePresetThumb(container, R.drawable.cape_preset_royal_blue, "cape_preset_royal_blue.png");
    }

    private void addCapePresetThumb(LinearLayout container, int drawableRes, String fileName) {
        addPresetThumb(container, drawableRes, () -> {
            mCapeUrlInput.setText(REPO_RAW_BASE + fileName);
            mCapePreview.setVisibility(View.VISIBLE);
            mCapePreview.setImageResource(drawableRes);
        });
    }

    private void buildCursorPresets(LinearLayout container) {
        addPresetThumb(container, R.drawable.ic_mouse_pointer, () -> selectCursorStyle("default"));
        addPresetThumb(container, R.drawable.cursor_nepal_flag, () -> selectCursorStyle("flag"));
        addPresetThumb(container, R.drawable.cursor_netherite_sword, () -> selectCursorStyle("sword"));
        addPresetThumb(container, R.drawable.cursor_enemy_eye, () -> selectCursorStyle("enemy"));
    }

    private void selectCursorStyle(String styleKey) {
        LauncherPreferences.DEFAULT_PREF.edit().putString("np_cursor_style", styleKey).apply();
        LauncherPreferences.PREF_CURSOR_STYLE = styleKey;
        Toast.makeText(requireContext(), R.string.skincape_saved, Toast.LENGTH_SHORT).show();
    }

    private void addPresetThumb(LinearLayout container, int drawableRes, Runnable onClick) {
        ImageView iv = new ImageView(requireContext());
        int size = (int) (56 * getResources().getDisplayMetrics().density);
        int margin = (int) (6 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(margin, margin, margin, margin);
        iv.setLayoutParams(lp);
        iv.setImageResource(drawableRes);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iv.setBackgroundResource(R.drawable.background_line);
        int pad = (int) (6 * getResources().getDisplayMetrics().density);
        iv.setPadding(pad, pad, pad, pad);
        iv.setOnClickListener(v -> onClick.run());
        container.addView(iv);
    }

    // ---------------------------------------------------------------------------------------
    // Image picking / uploading
    // ---------------------------------------------------------------------------------------

    private void onImagePicked(Uri uri) {
        if (uri == null || mPendingUploadTarget == null) return;

        if (mPendingUploadTarget == UploadTarget.CURSOR) {
            saveCursorImageLocally(uri);
            return;
        }

        ImageView previewTarget = mPendingUploadTarget == UploadTarget.SKIN ? mSkinPreview : mCapePreview;
        previewTarget.setVisibility(View.VISIBLE);
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(requireContext().getContentResolver(), uri);
            previewTarget.setImageBitmap(bitmap);
        } catch (IOException ignored) { }

        Toast.makeText(requireContext(), R.string.skincape_uploading, Toast.LENGTH_SHORT).show();
        UploadTarget target = mPendingUploadTarget;
        new Thread(() -> {
            String resultUrl = uploadToCatbox(uri);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (resultUrl != null) {
                    if (target == UploadTarget.SKIN) mSkinUrlInput.setText(resultUrl);
                    else mCapeUrlInput.setText(resultUrl);
                    Toast.makeText(requireContext(), R.string.skincape_upload_success, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(), R.string.skincape_upload_failed, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void saveCursorImageLocally(Uri uri) {
        try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open picked image");
            File outFile = new File(Tools.DIR_GAME_HOME, Touchpad.CUSTOM_CURSOR_FILE_NAME);
            File parent = outFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
            selectCursorStyle("custom");
        } catch (IOException e) {
            Log.e(TAG, "Failed to save custom cursor image", e);
            Toast.makeText(requireContext(), R.string.skincape_upload_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** Uploads image bytes to catbox.moe (no account/API key needed) and returns the resulting direct URL, or null on failure. */
    private String uploadToCatbox(Uri uri) {
        try {
            byte[] data = readAllBytes(uri);
            if (data == null) return null;

            String boundary = "----NPLauncherBoundary" + System.currentTimeMillis();
            URL url = new URL("https://catbox.moe/user/api.php");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);

            try (OutputStream os = connection.getOutputStream()) {
                writeFormField(os, boundary, "reqtype", "fileupload");
                writeFileField(os, boundary, "fileToUpload", "upload.png", data);
                os.write(("--" + boundary + "--\r\n").getBytes());
            }

            int code = connection.getResponseCode();
            InputStream responseStream = (code >= 200 && code < 300)
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = readStreamAsString(responseStream);
            connection.disconnect();

            if (response != null && response.trim().startsWith("http")) {
                return response.trim();
            }
            Log.e(TAG, "Upload host returned unexpected response: " + response);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Skin/cape upload failed", e);
            return null;
        }
    }

    private byte[] readAllBytes(Uri uri) throws IOException {
        try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private void writeFormField(OutputStream os, String boundary, String name, String value) throws IOException {
        os.write(("--" + boundary + "\r\n").getBytes());
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes());
        os.write((value + "\r\n").getBytes());
    }

    private void writeFileField(OutputStream os, String boundary, String fieldName, String fileName, byte[] data) throws IOException {
        os.write(("--" + boundary + "\r\n").getBytes());
        os.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n").getBytes());
        os.write("Content-Type: image/png\r\n\r\n".getBytes());
        os.write(data);
        os.write("\r\n".getBytes());
    }

    private String readStreamAsString(InputStream in) throws IOException {
        if (in == null) return null;
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString("UTF-8");
    }

    // ---------------------------------------------------------------------------------------
    // Config persistence (shared with the NP Skin mod)
    // ---------------------------------------------------------------------------------------

    private File getConfigFile() {
        File configDir = new File(Tools.DIR_GAME_HOME, CONFIG_DIR_NAME);
        return new File(configDir, CONFIG_FILE_NAME);
    }

    private void saveConfig() {
        try {
            JSONObject json = new JSONObject();
            json.put("skinUrl", mSkinUrlInput.getText().toString().trim());
            json.put("capeUrl", mCapeUrlInput.getText().toString().trim());

            File configFile = getConfigFile();
            File parent = configFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write(json.toString());
            }
            Toast.makeText(requireContext(), R.string.skincape_saved, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save skin/cape config", e);
            Toast.makeText(requireContext(), R.string.skincape_upload_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void loadExistingConfig() {
        try {
            File configFile = getConfigFile();
            if (!configFile.exists()) return;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            JSONObject json = new JSONObject(sb.toString());
            String skinUrl = json.optString("skinUrl", "");
            String capeUrl = json.optString("capeUrl", "");
            mSkinUrlInput.setText(skinUrl);
            mCapeUrlInput.setText(capeUrl);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load existing skin/cape config", e);
        }
    }
}
