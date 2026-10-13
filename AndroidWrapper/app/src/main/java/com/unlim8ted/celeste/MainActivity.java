package com.unlim8ted.celeste;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Button;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.GeckoResult;

public final class MainActivity extends Activity {
    private LocalAssetServer assetServer;
    private AndroidBridge bridge;
    private GeckoRuntime runtime;
    private GeckoSession session;
    private FrameLayout root;
    private GameFiles gameFiles;
    private TextView setupStatus;
    private SetupScreen setupScreen;
    private boolean importing;
    private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> pendingFileResult;
    private GeckoSession.PromptDelegate.FilePrompt pendingFilePrompt;

    @Override // android.app.Activity
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        root = new FrameLayout(this);
        root.setBackgroundColor(-16777216);
        setContentView(root);
        if (BuildConfig.PUBLIC_PACKAGE) {
            try {
                gameFiles = new GameFiles(getFilesDir(), getAssets().open("game-files.tsv"));
                if (!gameFiles.isReady()) { showSetup(); return; }
            } catch (Exception ex) { showError(ex); return; }
        }
        startGame();
    }

    private void startGame() {
        root.removeAllViews();
        try {
            this.bridge = new AndroidBridge(this);
            this.assetServer = new LocalAssetServer(this, "CelesteRuntime", this.bridge);
            this.assetServer.start();
            File geckoConfig = writeGeckoConfig();
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder().aboutConfigEnabled(true).consoleOutput(false).debugLogging(false).configFilePath(geckoConfig.getAbsolutePath()).build();
            this.runtime = GeckoRuntime.create(this, settings);
            this.session = new GeckoSession();
            this.session.setPromptDelegate(new GeckoSession.PromptDelegate() {
                @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession session, FilePrompt prompt) {
                    Log.i("CelesteFilePicker", "Web file prompt received");
                    if (pendingFileResult != null) return GeckoResult.fromValue(prompt.dismiss());
                    pendingFilePrompt = prompt;
                    pendingFileResult = new GeckoResult<>();
                    GeckoResult<PromptResponse> response = pendingFileResult;
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, prompt.type == FilePrompt.Type.MULTIPLE);
                    try { startActivityForResult(picker, 44); }
                    catch (Exception error) { finishFilePrompt(null); }
                    return response;
                }
            });
            this.session.open(this.runtime);
            GeckoView view = new GeckoView(this);
            view.setBackgroundColor(-16777216);
            root.addView((View) view, new FrameLayout.LayoutParams(-1, -1));
            view.setSession(this.session);
            this.session.loadUri(this.assetServer.getRootUrl());
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void showError(Exception ex) {
        TextView error = new TextView(this);
        error.setTextColor(-1); error.setPadding(32, 32, 32, 32);
        error.setText("Celeste failed to start.\n\n" + ex.getMessage()); root.addView(error);
    }

    private void showSetup() {
        setupScreen = new SetupScreen(this,
            () -> { if (!importing) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 43); },
            () -> { if (!importing) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE), 41); },
            BuildConfig.OWNERSHIP_SERVICE.isEmpty() ? null : button -> { button.setEnabled(false); verifySteam(button); });
        setupStatus = setupScreen.status;
        root.addView(setupScreen, new FrameLayout.LayoutParams(-1,-1));
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 44) {
            Uri selected = result == RESULT_OK && data != null ? data.getData() : null;
            Log.i("CelesteFilePicker", "Document picker returned result=" + result + " selected=" + (selected != null));
            if (selected == null) finishFilePrompt(null);
            else new Thread(() -> {
                try {
                    Uri local = copyWebSelection(selected);
                    runOnUiThread(() -> finishFilePrompt(local));
                } catch (Exception error) {
                    Log.e("CelesteFilePicker", "Could not copy selected file", error);
                    runOnUiThread(() -> finishFilePrompt(null));
                }
            }, "celeste-web-file-picker").start();
            return;
        }
        if (request == 43 && result == RESULT_OK && data != null && data.getData() != null && !importing) {
            importing = true; setupScreen.busy(true);
            setupStatus.setText("Opening encrypted game files...");
            Uri keyFile = data.getData();
            new Thread(() -> {
                try {
                    EncryptedImport.install(this, keyFile, gameFiles,
                        message -> runOnUiThread(() -> setupStatus.setText(message)));
                    runOnUiThread(() -> { importing = false; if (!isDestroyed()) startGame(); });
                } catch (Exception ex) {
                    runOnUiThread(() -> { importing = false; setupScreen.error("Unlock failed: " + ex.getMessage() + "\nSelect your original Gameplay0.data to try again."); });
                }
            }, "celeste-game-decrypt").start();
            return;
        }
        if (request != 41 || result != RESULT_OK || data == null || data.getData() == null || importing) return;
        importing = true; setupScreen.busy(true); setupStatus.setText("Checking and importing game files… Keep this app open.");
        new Thread(() -> {
            try {
                DesktopImport.install(this, data.getData(), gameFiles, message -> runOnUiThread(() -> setupStatus.setText(message)));
                runOnUiThread(() -> { importing = false; if (!isDestroyed()) startGame(); });
            } catch (Exception ex) {
                runOnUiThread(() -> { importing = false; setupScreen.error("Import failed: " + ex.getMessage() + "\nYour previous files were kept. Choose your desktop Celeste ZIP or select Gameplay0.data directly."); });
            }
        }, "celeste-game-import").start();
    }

    private void finishFilePrompt(Uri selected) {
        GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result = pendingFileResult;
        GeckoSession.PromptDelegate.FilePrompt prompt = pendingFilePrompt;
        pendingFileResult = null;
        pendingFilePrompt = null;
        if (result != null && prompt != null) {
            try { result.complete(selected == null ? prompt.dismiss() : prompt.confirm(this, selected)); }
            catch (Exception error) {
                Log.e("CelesteFilePicker", "Could not pass selected file to GeckoView", error);
                result.complete(prompt.dismiss());
            }
        }
    }

    private Uri copyWebSelection(Uri source) throws IOException {
        String name = "selected-file.zip";
        try (Cursor cursor = getContentResolver().query(source, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        }
        name = name == null ? "selected-file.zip" : name.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "selected-file.zip";
        File directory = new File(getCacheDir(), "web-picked-files");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create file picker cache");
        File target = new File(directory, name);
        try {
            try (InputStream in = getContentResolver().openInputStream(source);
                 OutputStream out = new FileOutputStream(target)) {
                if (in == null) throw new IOException("Cannot read selected file");
                byte[] buffer = new byte[131072];
                long size = 0; int count;
                while ((count = in.read(buffer)) != -1) {
                    size += count;
                    if (size > 1_000_000_000L) throw new IOException("Selected file exceeds 1 GB");
                    out.write(buffer, 0, count);
                }
            }
        } catch (IOException error) {
            if (target.exists()) target.delete();
            throw error;
        }
        Log.i("CelesteFilePicker", "Copied web selection: " + target.length() + " bytes");
        return Uri.fromFile(target);
    }

    private JSONObject service(String path, boolean post) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BuildConfig.OWNERSHIP_SERVICE + path).openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(post ? "POST" : "GET");
        try (java.io.InputStream in = connection.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) { if (out.size() + n > 16384) throw new IOException("Invalid service response"); out.write(buffer, 0, n); }
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }

    private void verifySteam(Button button) {
        new Thread(() -> {
            try {
                JSONObject created = service("/sessions", true);
                String id = created.getString("id");
                if (!id.matches("[A-Za-z0-9_-]{40,80}")) throw new IOException("Invalid session");
                // Always navigate to our configured origin, never a URL supplied by a response.
                runOnUiThread(() -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.OWNERSHIP_SERVICE + "/login/" + id))); }
                    catch (Exception e) { setupStatus.setText("No browser available. You can still import your files."); }
                });
                for (int i = 0; i < 120 && !isDestroyed(); i++) {
                    Thread.sleep(5000);
                    String status = service("/sessions/" + id, false).getString("status");
                    if (!status.equals("pending")) {
                        runOnUiThread(() -> { if (session == null && !importing) setupStatus.setText(status.equals("verified")
                            ? "Steam ownership verified. Now import your compatible game files to play."
                            : "Steam could not verify ownership (private library, missing game, or expired login). You can still import your own game files."); });
                        return;
                    }
                }
                runOnUiThread(() -> { if (session == null && !importing) setupStatus.setText("Steam login expired. Try again or import your own game files."); });
            } catch (Exception ex) {
                runOnUiThread(() -> { if (session == null && !importing) setupStatus.setText("Steam verification unavailable. You can still import your own game files."); });
            } finally { runOnUiThread(() -> button.setEnabled(true)); }
        }, "celeste-store-verification").start();
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        finishFilePrompt(null);
        if (this.session != null) {
            this.session.close();
            this.session = null;
        }
        if (this.assetServer != null) {
            this.assetServer.stop();
            this.assetServer = null;
        }
        if (this.bridge != null) {
            this.bridge.stop();
            this.bridge = null;
        }
        super.onDestroy();
    }

    private void configureWindow() {
        requestWindowFeature(1);
        Window window = getWindow();
        window.setFlags(1024, 1024);
        window.addFlags(128);
        window.getDecorView().setSystemUiVisibility(5894);
    }

    private File writeGeckoConfig() throws IOException {
        File config = new File(getFilesDir(), "geckoview-config.yaml");
        FileWriter writer = new FileWriter(config, false);
        try {
            writer.write("prefs:\n");
            writer.write("  javascript.options.shared_memory: true\n");
            writer.write("  javascript.options.wasm_threads: true\n");
            writer.write("  dom.workers.maxPerDomain: 8\n");
            writer.write("  gfx.webrender.program-binary-cache.enabled: false\n");
            writer.write("  webgl.program-binary-cache.enabled: false\n");
            writer.write("  gfx.webrender.program-binary: false\n");
            writer.write("  gfx.webrender.program-binary-disk: false\n");
            writer.close();
            return config;
        } catch (Throwable th) {
            try {
                writer.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
            throw th;
        }
    }
}
