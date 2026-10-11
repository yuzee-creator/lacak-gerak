package com.lacakgerak.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import androidx.activity.result.ActivityResult;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONObject;

@CapacitorPlugin(name = "NativeTracker")
public class NativeTrackerPlugin extends Plugin {
    static NativeTrackerPlugin instance;

    @Override
    public void load() { instance = this; }

    static void emitProgress(float p) {
        if (instance == null) return;
        JSObject o = new JSObject();
        o.put("p", (double) p);
        instance.notifyListeners("progress", o);
    }

    static void emitDone(String status) {
        if (instance == null) return;
        JSObject o = new JSObject();
        o.put("status", status);
        instance.notifyListeners("done", o);
    }

    private File resultFile() { return new File(getContext().getCacheDir(), "track_result.json"); }

    @PluginMethod
    public void pickVideo(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        startActivityForResult(call, i, "pickDone");
    }

    @ActivityCallback
    private void pickDone(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            call.reject("Dibatalkan");
            return;
        }
        final Uri uri = result.getData().getData();
        new Thread(() -> {
            try {
                File dir = getContext().getCacheDir();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) if (f.getName().startsWith("src_")) f.delete();
                File dst = new File(dir, "src_" + System.currentTimeMillis() + ".mp4");
                InputStream in = getContext().getContentResolver().openInputStream(uri);
                OutputStream out = new FileOutputStream(dst);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
                JSObject o = new JSObject();
                o.put("path", dst.getAbsolutePath());
                call.resolve(o);
            } catch (Exception e) {
                call.reject(String.valueOf(e.getMessage()));
            }
        }).start();
    }

    @PluginMethod
    public void startTrack(PluginCall call) {
        String path = call.getString("path");
        Double t0 = call.getDouble("t0", 0.0);
        JSArray pts = call.getArray("pts");
        if (path == null || pts == null || pts.length() == 0) { call.reject("Data kurang"); return; }
        if (TrackService.running) { call.reject("Masih melacak"); return; }
        try {
            double[] a = new double[pts.length() * 2];
            for (int i = 0; i < pts.length(); i++) {
                JSONObject o = pts.getJSONObject(i);
                a[2 * i] = o.getDouble("x");
                a[2 * i + 1] = o.getDouble("y");
            }
            resultFile().delete();
            if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(getContext(), "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(getActivity(), new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
            }
            Intent in = new Intent(getContext(), TrackService.class);
            in.putExtra("path", path);
            in.putExtra("t0", t0.doubleValue());
            in.putExtra("pts", a);
            ContextCompat.startForegroundService(getContext(), in);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void cancelTrack(PluginCall call) {
        TrackService.cancel = true;
        call.resolve();
    }

    @PluginMethod
    public void getResult(PluginCall call) {
        JSObject o = new JSObject();
        o.put("running", TrackService.running);
        o.put("progress", (double) TrackService.progress);
        File f = resultFile();
        if (f.exists()) {
            try {
                FileInputStream in = new FileInputStream(f);
                byte[] b = new byte[(int) f.length()];
                int off = 0;
                while (off < b.length) { int r = in.read(b, off, b.length - off); if (r < 0) break; off += r; }
                in.close();
                o.put("result", new JSObject(new String(b, "UTF-8")));
            } catch (Exception ignored) { }
        }
        call.resolve(o);
    }

    @PluginMethod
    public void clearResult(PluginCall call) {
        resultFile().delete();
        call.resolve();
    }
}
