package com.example.foldblur;

import android.app.Activity;
import android.app.Presentation;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity implements SensorEventListener, DisplayManager.DisplayListener {
    private static final String BUILT_IN_DISPLAYS = "android.hardware.display.category.BUILT_IN_DISPLAYS";
    private static final float ENDPOINT_CLOSED = 2.0f;
    private static final float ENDPOINT_OPEN = 178.0f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Integer, DuoPresentation> presentations = new HashMap<>();
    private SensorManager sensorManager;
    private Sensor hingeSensor;
    private DisplayManager displayManager;
    private HomeSurface mainSurface;
    private float lastAngle = 180f;
    private long lastSensorFrameMs = 0L;
    private boolean launcherVisible = false;
    private Runnable dismissSecondaryRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow(getWindow());

        sensorManager = getSystemService(SensorManager.class);
        displayManager = getSystemService(DisplayManager.class);
        hingeSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);

        if (displayManager != null) {
            displayManager.registerDisplayListener(this, handler);
        }

        renderForCurrentRole();
    }

    @Override
    protected void onStart() {
        super.onStart();
        launcherVisible = true;
        if (sensorManager != null && hingeSensor != null) {
            sensorManager.registerListener(this, hingeSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onStop() {
        launcherVisible = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        dismissAllPresentations();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (displayManager != null) displayManager.unregisterDisplayListener(this);
        dismissAllPresentations();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderForCurrentRole();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (isHomeRoleHeld()) {
            renderHome();
            if (hingeSensor == null && mainSurface != null) mainSurface.pulseTransition();
        }
        syncPresentations();
    }

    private void configureWindow(Window window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
    }

    private void renderForCurrentRole() {
        if (isHomeRoleHeld()) renderHome();
        else renderSetup();
    }

    private void renderSetup() {
        dismissAllPresentations();
        int pad = dp(this, 24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(this, 50), pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(245, 245, 247));

        TextView title = new TextView(this);
        title.setText("Fold Duo Launcher");
        title.setTextSize(30);
        title.setTextColor(Color.BLACK);
        root.addView(title, matchWrap());

        TextView body = new TextView(this);
        String hinge = hingeSensor == null ? "No continuous hinge sensor detected." : "Continuous hinge sensor detected.";
        body.setText("This version blurs and transforms its own live Home screen, so it does not depend on Samsung cross-window blur.\n\n" + hinge +
                "\n\nFor the fold animation to control the actual Home surface, Fold Duo must be your default Home app.");
        body.setTextSize(17);
        body.setTextColor(Color.DKGRAY);
        LinearLayout.LayoutParams bp = matchWrap();
        bp.topMargin = dp(this, 18);
        root.addView(body, bp);

        Button makeHome = new Button(this);
        makeHome.setText("MAKE FOLD DUO MY HOME APP");
        makeHome.setOnClickListener(v -> requestHomeRole());
        LinearLayout.LayoutParams mp = matchWrap();
        mp.topMargin = dp(this, 26);
        root.addView(makeHome, mp);

        Button preview = new Button(this);
        preview.setText("PREVIEW HOME SCREEN");
        preview.setOnClickListener(v -> renderHome());
        LinearLayout.LayoutParams pp = matchWrap();
        pp.topMargin = dp(this, 10);
        root.addView(preview, pp);

        setContentView(root);
        mainSurface = null;
    }

    private void renderHome() {
        HomeSurface surface = new HomeSurface(this, isWideDisplay(getDisplay()), false);
        surface.setOnLongClickListener(v -> {
            renderSetup();
            return true;
        });
        setContentView(surface);
        mainSurface = surface;
        surface.applyHinge(lastAngle);
        syncPresentations();
    }

    private boolean isHomeRoleHeld() {
        try {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return rm.isRoleHeld(RoleManager.ROLE_HOME);
            }
        } catch (Throwable ignored) {}

        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return info != null && info.activityInfo != null && getPackageName().equals(info.activityInfo.packageName);
    }

    private void requestHomeRole() {
        try {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_HOME));
                return;
            }
        } catch (Throwable ignored) {}
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (Throwable t) {
            Toast.makeText(this, "Open Settings > Apps > Choose default apps > Home app", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_HINGE_ANGLE || event.values.length == 0) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSensorFrameMs < 10) return;
        lastSensorFrameMs = now;

        float angle = clamp(event.values[0], 0f, 180f);
        lastAngle = angle;

        if (mainSurface != null) mainSurface.applyHinge(angle);

        if (angle > ENDPOINT_CLOSED && angle < ENDPOINT_OPEN) {
            cancelPendingDismiss();
            syncPresentations();
        } else {
            scheduleSecondaryDismiss();
        }

        for (DuoPresentation p : new ArrayList<>(presentations.values())) {
            p.applyHinge(angle);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onDisplayAdded(int displayId) {
        if (launcherVisible) syncPresentations();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        DuoPresentation p = presentations.remove(displayId);
        if (p != null) safeDismiss(p);
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (launcherVisible) syncPresentations();
    }

    private void syncPresentations() {
        if (!launcherVisible || displayManager == null || mainSurface == null) return;
        if (lastAngle <= ENDPOINT_CLOSED || lastAngle >= ENDPOINT_OPEN) return;

        int currentId = getDisplay() == null ? Display.DEFAULT_DISPLAY : getDisplay().getDisplayId();
        Map<Integer, Display> candidates = new HashMap<>();

        try {
            for (Display d : displayManager.getDisplays(BUILT_IN_DISPLAYS)) {
                if (d != null) candidates.put(d.getDisplayId(), d);
            }
        } catch (Throwable ignored) {}

        // On Android versions that expose the built-in display category, the call above
        // can include inactive foldable panels. Avoid Display#getType/TYPE_INTERNAL here:
        // those are hidden/Test APIs and are not available to normal third-party apps.

        // Also include any system-approved presentation displays as a public-API fallback.
        try {
            for (Display d : displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
                if (d != null) candidates.put(d.getDisplayId(), d);
            }
        } catch (Throwable ignored) {}

        Set<Integer> keep = new HashSet<>();
        for (Display d : candidates.values()) {
            if (d.getDisplayId() == currentId || !d.isValid()) continue;
            keep.add(d.getDisplayId());
            if (!presentations.containsKey(d.getDisplayId())) {
                try {
                    DuoPresentation p = new DuoPresentation(this, d);
                    p.show();
                    presentations.put(d.getDisplayId(), p);
                    p.applyHinge(lastAngle);
                } catch (Throwable ignored) {
                    // Samsung decides whether an inactive physical panel is presentation-capable.
                }
            }
        }

        for (Integer id : new HashSet<>(presentations.keySet())) {
            DuoPresentation p = presentations.get(id);
            if (p == null || !p.getDisplay().isValid() || (!keep.contains(id) && id == currentId)) {
                presentations.remove(id);
                if (p != null) safeDismiss(p);
            }
        }
    }

    private void scheduleSecondaryDismiss() {
        cancelPendingDismiss();
        dismissSecondaryRunnable = () -> {
            if (lastAngle <= ENDPOINT_CLOSED || lastAngle >= ENDPOINT_OPEN) {
                dismissAllPresentations();
            }
        };
        handler.postDelayed(dismissSecondaryRunnable, 550);
    }

    private void cancelPendingDismiss() {
        if (dismissSecondaryRunnable != null) {
            handler.removeCallbacks(dismissSecondaryRunnable);
            dismissSecondaryRunnable = null;
        }
    }

    private void dismissAllPresentations() {
        cancelPendingDismiss();
        for (DuoPresentation p : new ArrayList<>(presentations.values())) safeDismiss(p);
        presentations.clear();
    }

    private static void safeDismiss(Presentation p) {
        try { p.dismiss(); } catch (Throwable ignored) {}
    }

    private class DuoPresentation extends Presentation {
        private HomeSurface surface;

        DuoPresentation(Context outerContext, Display display) {
            super(outerContext, display);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            Window w = getWindow();
            if (w != null) {
                configureWindow(w);
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.dimAmount = 0f;
                w.setAttributes(lp);
            }
            surface = new HomeSurface(getContext(), isWideDisplay(getDisplay()), true);
            setContentView(surface);
        }

        void applyHinge(float angle) {
            if (surface != null) surface.applyHinge(angle);
        }
    }

    private static class HomeSurface extends FrameLayout {
        private final boolean wide;
        private final boolean presentation;
        private final FrameLayout visualLayer;
        private final int maxBlurPx;

        HomeSurface(Context context, boolean wide, boolean presentation) {
            super(context);
            this.wide = wide;
            this.presentation = presentation;
            this.maxBlurPx = dp(context, wide ? 76 : 62);
            setBackgroundColor(Color.TRANSPARENT);
            setClickable(true);
            setLongClickable(true);

            visualLayer = new FrameLayout(context);
            visualLayer.setBackgroundColor(Color.rgb(18, 18, 22));
            visualLayer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            addView(visualLayer, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            addWallpaper(context, visualLayer);
            addApps(context, visualLayer, wide);
        }

        private void addWallpaper(Context context, FrameLayout parent) {
            ImageView wallpaper = new ImageView(context);
            wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
            try {
                Drawable d = android.app.WallpaperManager.getInstance(context).getDrawable();
                wallpaper.setImageDrawable(d);
            } catch (Throwable ignored) {
                GradientDrawable bg = new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        new int[]{Color.rgb(29, 31, 42), Color.rgb(9, 10, 14)});
                wallpaper.setImageDrawable(bg);
            }
            parent.addView(wallpaper, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            View scrim = new View(context);
            scrim.setBackgroundColor(Color.argb(42, 0, 0, 0));
            parent.addView(scrim, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }

        private void addApps(Context context, FrameLayout parent, boolean wide) {
            ScrollView scroll = new ScrollView(context);
            scroll.setFillViewport(true);
            scroll.setClipToPadding(false);

            GridLayout grid = new GridLayout(context);
            int columns = wide ? 7 : 4;
            grid.setColumnCount(columns);
            grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
            grid.setUseDefaultMargins(false);
            int hPad = dp(context, wide ? 28 : 14);
            grid.setPadding(hPad, dp(context, wide ? 72 : 58), hPad, dp(context, 120));

            List<ResolveInfo> apps = queryApps(context);
            int iconPx = dp(context, wide ? 58 : 54);
            int cellWidth = dp(context, wide ? 112 : 86);
            int cellHeight = dp(context, wide ? 110 : 106);

            for (ResolveInfo ri : apps) {
                if (ri.activityInfo == null || ri.activityInfo.packageName.equals(context.getPackageName())) continue;

                LinearLayout cell = new LinearLayout(context);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(context, 4), dp(context, 5), dp(context, 4), dp(context, 4));

                ImageView icon = new ImageView(context);
                try { icon.setImageDrawable(ri.loadIcon(context.getPackageManager())); } catch (Throwable ignored) {}
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(iconPx, iconPx);
                cell.addView(icon, ip);

                TextView label = new TextView(context);
                CharSequence labelText;
                try { labelText = ri.loadLabel(context.getPackageManager()); } catch (Throwable t) { labelText = ri.activityInfo.packageName; }
                label.setText(labelText);
                label.setTextSize(wide ? 12 : 11);
                label.setTextColor(Color.WHITE);
                label.setGravity(Gravity.CENTER);
                label.setSingleLine(true);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                label.setShadowLayer(3f, 0f, 1f, Color.BLACK);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cellWidth - dp(context, 8), LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(context, 5);
                cell.addView(label, lp);

                ComponentName component = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                cell.setOnClickListener(v -> launchComponent(context, component));

                GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
                gp.width = cellWidth;
                gp.height = cellHeight;
                gp.setGravity(Gravity.CENTER);
                grid.addView(cell, gp);
            }

            scroll.addView(grid, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            parent.addView(scroll, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            TextView hint = new TextView(context);
            hint.setText(presentation ? "" : "Long-press anywhere for Fold Duo setup");
            hint.setTextSize(11);
            hint.setTextColor(Color.argb(150, 255, 255, 255));
            hint.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(context, 42), Gravity.BOTTOM);
            hp.bottomMargin = dp(context, 16);
            parent.addView(hint, hp);
        }

        void applyHinge(float angle) {
            float t = clamp(angle / 180f, 0f, 1f);
            float mid = (float) Math.pow(Math.max(0f, Math.sin(Math.PI * t)), 0.88);
            float radius = maxBlurPx * mid;

            if (radius > 0.75f) {
                visualLayer.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
            } else {
                visualLayer.setRenderEffect(null);
            }

            visualLayer.setPivotX(0f); // left edge stays visually anchored, like the Duo-style expansion.
            visualLayer.setPivotY(getHeight() > 0 ? getHeight() * 0.5f : 0f);

            if (wide) {
                float reveal = smoothStep(0.10f, 0.90f, t);
                visualLayer.setScaleX(0.82f + 0.18f * reveal);
                visualLayer.setScaleY(0.97f + 0.03f * reveal);
                visualLayer.setAlpha(0.74f + 0.26f * reveal);
            } else {
                float stretch = smoothStep(0.04f, 0.72f, t);
                visualLayer.setScaleX(1.00f + 0.15f * stretch);
                visualLayer.setScaleY(1.00f - 0.025f * mid);
                visualLayer.setAlpha(1.00f - 0.10f * smoothStep(0.48f, 0.88f, t));
            }
        }

        void pulseTransition() {
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f, 0f);
            a.setDuration(360);
            a.addUpdateListener(v -> {
                float p = (Float) v.getAnimatedValue();
                float r = maxBlurPx * p;
                if (r > 0.75f) visualLayer.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
                else visualLayer.setRenderEffect(null);
            });
            a.start();
        }
    }

    private static List<ResolveInfo> queryApps(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> result;
        try {
            result = new ArrayList<>(pm.queryIntentActivities(query, PackageManager.MATCH_ALL));
        } catch (Throwable t) {
            result = new ArrayList<>();
        }
        Collections.sort(result, Comparator.comparing(r -> {
            try { return r.loadLabel(pm).toString().toLowerCase(); }
            catch (Throwable ignored) { return r.activityInfo == null ? "" : r.activityInfo.packageName; }
        }));
        return result;
    }

    private static void launchComponent(Context context, ComponentName component) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(component);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(context, "Could not open app", Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean isWideDisplay(Display display) {
        if (display == null) return false;
        try {
            Display.Mode mode = display.getMode();
            float w = mode.getPhysicalWidth();
            float h = mode.getPhysicalHeight();
            float ratio = Math.min(w, h) / Math.max(w, h);
            return ratio > 0.62f;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static float smoothStep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
