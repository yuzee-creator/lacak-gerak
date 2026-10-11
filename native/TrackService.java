package com.lacakgerak.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class TrackService extends Service {
    static final String CH = "lg_track";
    static final int ID = 7;
    public static volatile boolean running = false, cancel = false;
    public static volatile float progress = 0;
    private PowerManager.WakeLock wl;

    @Override
    public IBinder onBind(Intent i) { return null; }

    private Notification build(String text, int pct, boolean ongoing) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Lacak Gerak")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing);
        if (ongoing) b.setProgress(100, pct, false);
        Intent li = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (li != null) b.setContentIntent(PendingIntent.getActivity(this, 0, li, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return b.build();
    }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        if (in == null) { stopSelf(); return START_NOT_STICKY; }
        final NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "Pelacakan", NotificationManager.IMPORTANCE_LOW));
        }
        Notification first = build("Memulai pelacakan…", 0, true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID, first);

        final String path = in.getStringExtra("path");
        final double t0 = in.getDoubleExtra("t0", 0);
        final double[] pts = in.getDoubleArrayExtra("pts");
        cancel = false;
        running = true;
        progress = 0;
        wl = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lg:track");
        wl.acquire(60 * 60 * 1000L);

        new Thread(() -> {
            String status = "done";
            JSONObject res = new JSONObject();
            try {
                double[][] st = new double[pts.length / 2][2];
                for (int i = 0; i < st.length; i++) { st[i][0] = pts[2 * i]; st[i][1] = pts[2 * i + 1]; }
                List<List<double[]>> out = Tracker.run(path, t0, st, new Tracker.Cb() {
                    long lu = 0;
                    public void progress(float p) {
                        TrackService.progress = p;
                        long now = System.currentTimeMillis();
                        if (now - lu > 1000) {
                            lu = now;
                            nm.notify(ID, build("Melacak " + Math.round(p * 100) + "%", Math.round(p * 100), true));
                            NativeTrackerPlugin.emitProgress(p);
                        }
                    }
                    public boolean cancelled() { return cancel; }
                });
                JSONArray all = new JSONArray();
                for (List<double[]> l : out) {
                    JSONArray a = new JSONArray();
                    for (double[] s : l) { JSONArray q = new JSONArray(); q.put(s[0]); q.put(s[1]); q.put(s[2]); a.put(q); }
                    all.put(a);
                }
                res.put("tracks", all);
                if (cancel) status = "cancelled";
            } catch (Throwable e) {
                status = "error";
                try { res.put("error", String.valueOf(e)); } catch (Exception ignored) { }
            }
            try {
                res.put("status", status);
                FileOutputStream fo = new FileOutputStream(new File(getCacheDir(), "track_result.json"));
                fo.write(res.toString().getBytes("UTF-8"));
                fo.close();
            } catch (Exception ignored) { }
            running = false;
            stopForeground(false);
            nm.notify(ID, build(status.equals("error") ? "Pelacakan gagal" : "Pelacakan selesai, buka aplikasi", 0, false));
            if (wl != null && wl.isHeld()) wl.release();
            NativeTrackerPlugin.emitDone(status);
            stopSelf();
        }).start();
        return START_NOT_STICKY;
    }
}
