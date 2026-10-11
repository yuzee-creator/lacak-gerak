package com.lacakgerak.app;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class Tracker {
    public interface Cb { void progress(float p); boolean cancelled(); }

    private static final int R = 8, TS = 17 * 17;

    private static class St { float x, y; float[] tpl = new float[TS]; int sr = 22; boolean init; }

    private static double[] toRaw(double x, double y, int rot) {
        switch (rot) {
            case 90: return new double[]{y, 1 - x};
            case 180: return new double[]{1 - x, 1 - y};
            case 270: return new double[]{1 - y, x};
            default: return new double[]{x, y};
        }
    }

    private static double[] toDisp(double x, double y, int rot) {
        switch (rot) {
            case 90: return new double[]{1 - y, x};
            case 180: return new double[]{1 - x, 1 - y};
            case 270: return new double[]{y, 1 - x};
            default: return new double[]{x, y};
        }
    }

    private static void fill(Image img, float[] g, int tw, int th) {
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer b = p.getBuffer();
        int rs = p.getRowStride(), ps = p.getPixelStride(), w = img.getWidth(), h = img.getHeight();
        for (int y = 0; y < th; y++) {
            int base = (y * h / th) * rs, o = y * tw;
            for (int x = 0; x < tw; x++) g[o + x] = b.get(base + (x * w / tw) * ps) & 0xFF;
        }
    }

    private static void patch(float[] g, int w, int h, float cx, float cy, float[] out) {
        int k = 0, ix = Math.round(cx), iy = Math.round(cy);
        for (int j = -R; j <= R; j++)
            for (int i = -R; i <= R; i++) {
                int px = Math.min(w - 1, Math.max(0, ix + i)), py = Math.min(h - 1, Math.max(0, iy + j));
                out[k++] = g[py * w + px];
            }
    }

    private static void match(float[] g, int w, int h, St s) {
        int cx0 = Math.round(s.x), cy0 = Math.round(s.y), bx = cx0, by = cy0;
        float bs = Float.MAX_VALUE;
        for (int dy = -s.sr; dy <= s.sr; dy++)
            for (int dx = -s.sr; dx <= s.sr; dx++) {
                int X = cx0 + dx, Y = cy0 + dy;
                if (X < R || Y < R || X >= w - R || Y >= h - R) continue;
                float sum = 0;
                int k = 0;
                for (int y = -R; y <= R; y++) {
                    int o = (Y + y) * w + X;
                    for (int x = -R; x <= R; x++, k++) sum += Math.abs(g[o + x] - s.tpl[k]);
                    if (sum >= bs) break;
                }
                if (sum < bs) { bs = sum; bx = X; by = Y; }
            }
        s.x = bx; s.y = by;
    }

    /** start[i] = {x,y} normalisasi (0..1) pada tampilan video. Hasil: per titik daftar {t,x,y}. */
    public static List<List<double[]>> run(String path, double t0, double[][] start, Cb cb) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(path);
            int tr = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) { tr = i; fmt = f; break; }
            }
            if (tr < 0) throw new Exception("Tidak ada track video");
            ex.selectTrack(tr);
            int rot = fmt.containsKey(MediaFormat.KEY_ROTATION) ? fmt.getInteger(MediaFormat.KEY_ROTATION) : 0;
            double dur = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) / 1e6 : 0;
            ex.seekTo((long) (t0 * 1e6), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            int n = start.length;
            St[] st = new St[n];
            List<List<double[]>> out = new ArrayList<>();
            for (int i = 0; i < n; i++) { st[i] = new St(); out.add(new ArrayList<double[]>()); }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            int tw = 0, th = 0;
            float[] g = null, np = new float[TS];
            double last = -1, minStep = 1.0 / 30 - 0.004;

            while (!outDone && !cb.cancelled()) {
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int sz = ex.readSampleData(ib, 0);
                        if (sz < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                    double t = info.presentationTimeUs / 1e6;
                    if (info.size > 0 && t >= t0 - 0.001 && (last < 0 || t - last >= minStep)) {
                        Image img = codec.getOutputImage(oi);
                        if (img != null) {
                            int w = img.getWidth(), h = img.getHeight();
                            if (g == null) {
                                tw = Math.min(320, w);
                                th = Math.max(16, Math.round((float) tw * h / w));
                                g = new float[tw * th];
                            }
                            fill(img, g, tw, th);
                            img.close();
                            double gap = last < 0 ? 0 : t - last;
                            for (int i = 0; i < n; i++) {
                                St s = st[i];
                                if (!s.init) {
                                    double[] r = toRaw(start[i][0], start[i][1], rot);
                                    s.x = (float) (r[0] * tw);
                                    s.y = (float) (r[1] * th);
                                    patch(g, tw, th, s.x, s.y, s.tpl);
                                    s.init = true;
                                } else {
                                    s.sr = Math.min(40, Math.round((float) (18 + gap * 80)));
                                    match(g, tw, th, s);
                                    patch(g, tw, th, s.x, s.y, np);
                                    for (int q = 0; q < TS; q++) s.tpl[q] = s.tpl[q] * 0.9f + np[q] * 0.1f;
                                }
                                double[] d = toDisp((double) s.x / tw, (double) s.y / th, rot);
                                out.get(i).add(new double[]{t, d[0], d[1]});
                            }
                            last = t;
                            if (dur > t0) cb.progress((float) Math.min(1.0, (t - t0) / (dur - t0)));
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                }
            }
            return out;
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) { }
            ex.release();
        }
    }
}
