package com.unlim8ted.celeste;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

public final class MainActivity extends Activity {
    private LocalAssetServer assetServer;
    private AndroidBridge bridge;
    private GeckoRuntime runtime;
    private GeckoSession session;
    private FrameLayout root;
    private GameFiles gameFiles;
    private TextView setupStatus;
    private boolean importing;

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
        LinearLayout setup = new LinearLayout(this);
        setup.setOrientation(LinearLayout.VERTICAL); setup.setPadding(32, 24, 32, 24);
        setupStatus = new TextView(this); setupStatus.setTextColor(-1); setupStatus.setTextSize(18);
        setupStatus.setText("Unlock Celeste on your phone\n\nSelect Gameplay0.data from Content/Graphics/Atlases in your own Celeste installation. " +
            "The encrypted game data is included in this app. You only need to copy your atlas file to your phone.\n\nBuild " + BuildConfig.VERSION_NAME);
        setup.addView(setupStatus);
        Button decrypt = new Button(this); decrypt.setText("Unlock with my game file");
        decrypt.setOnClickListener(v -> {
            if (!importing) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 43);
        }); setup.addView(decrypt);
        Button importButton = new Button(this); importButton.setText("Import my game files");
        importButton.setOnClickListener(v -> {
            if (importing) return;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, 41);
        }); setup.addView(importButton);
        if (!BuildConfig.OWNERSHIP_SERVICE.isEmpty()) {
            Button steam = new Button(this); steam.setText("Verify with Steam (optional)");
            steam.setOnClickListener(v -> { steam.setEnabled(false); verifySteam(steam); }); setup.addView(steam);
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(setup); root.addView(scroll);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 43 && result == RESULT_OK && data != null && data.getData() != null && !importing) {
            importing = true;
            setupStatus.setText("Opening encrypted game files...");
            Uri keyFile = data.getData();
            new Thread(() -> {
                try {
                    EncryptedImport.install(this, keyFile, gameFiles,
                        message -> runOnUiThread(() -> setupStatus.setText(message)));
                    runOnUiThread(() -> { importing = false; if (!isDestroyed()) startGame(); });
                } catch (Exception ex) {
                    runOnUiThread(() -> { importing = false; setupStatus.setText("Unlock failed: " + ex.getMessage() + "\nSelect your original Gameplay0.data to try again."); });
                }
            }, "celeste-game-decrypt").start();
            return;
        }
        if (request != 41 || result != RESULT_OK || data == null || data.getData() == null || importing) return;
        importing = true; setupStatus.setText("Checking and importing game files… Keep this app open.");
        new Thread(() -> {
            try {
                gameFiles.install(getContentResolver().openInputStream(data.getData()));
                runOnUiThread(() -> { importing = false; if (!isDestroyed()) startGame(); });
            } catch (Exception ex) {
                runOnUiThread(() -> { importing = false; setupStatus.setText("Import failed: " + ex.getMessage() + "\nYour previous files were kept. Choose a compatible ZIP and try again."); });
            }
        }, "celeste-game-import").start();
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
