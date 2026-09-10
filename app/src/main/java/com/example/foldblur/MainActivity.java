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

    private static final String BUILT_IN_DISPLAYS =
            "android.hardware.display.category.BUILT_IN_DISPLAYS";

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

        hingeSensor = sensorManager == null
                ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);

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
            sensorManager.registerListener(
                    this,
                    hingeSensor,
                    SensorManager.SENSOR_DELAY_GAME
            );
        }
    }

    @Override
    protected void onStop() {
        launcherVisible = false;

        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }

        dismissAllPresentations();

        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (displayManager != null) {
            displayManager.unregisterDisplayListener(this);
        }

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

            if (hingeSensor == null && mainSurface != null) {
                mainSurface.pulseTransition();
            }
        }

        syncPresentations();
    }

    private void configureWindow(Window window) {
        if (window == null) {
            return;
        }

        window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        );

        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
    }

    private void renderForCurrentRole() {
        if (isHomeRoleHeld()) {
            renderHome();
        } else {
            renderSetup();
        }
    }

    private void renderSetup() {
        dismissAllPresentations();

        int pad = dp(this, 24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(
                pad,
                dp(this, 50),
                pad,
                pad
        );
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(245, 245, 247));

        TextView title = new TextView(this);
        title.setText("Fold Duo Launcher");
        title.setTextSize(30);
        title.setTextColor(Color.BLACK);

        root.addView(title, matchWrap());

        TextView body = new TextView(this);

        String hingeStatus =
                hingeSensor == null
                        ? "No continuous hinge sensor detected."
                        : "Continuous hinge sensor detected.";

        body.setText(
                "Fold Duo controls its own live Home screen and applies the fold animation directly to that surface.\n\n"
                        + hingeStatus
                        + "\n\nFor the effect to control your Home screen, Fold Duo must be selected as the default Home app."
        );

        body.setTextSize(17);
        body.setTextColor(Color.DKGRAY);

        LinearLayout.LayoutParams bodyParams = matchWrap();
        bodyParams.topMargin = dp(this, 18);

        root.addView(body, bodyParams);

        Button makeHome = new Button(this);
        makeHome.setText("MAKE FOLD DUO MY HOME APP");
        makeHome.setOnClickListener(v -> requestHomeRole());

        LinearLayout.LayoutParams makeHomeParams = matchWrap();
        makeHomeParams.topMargin = dp(this, 26);

        root.addView(makeHome, makeHomeParams);

        Button preview = new Button(this);
        preview.setText("PREVIEW HOME SCREEN");
        preview.setOnClickListener(v -> renderHome());

        LinearLayout.LayoutParams previewParams = matchWrap();
        previewParams.topMargin = dp(this, 10);

        root.addView(preview, previewParams);

        setContentView(root);

        mainSurface = null;
    }

    private void renderHome() {
        HomeSurface surface =
                new HomeSurface(
                        this,
                        isWideDisplay(getDisplay()),
                        false
                );

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
            RoleManager roleManager = getSystemService(RoleManager.class);

            if (roleManager != null &&
                    roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {

                return roleManager.isRoleHeld(RoleManager.ROLE_HOME);
            }

        } catch (Throwable ignored) {
        }

        Intent homeIntent =
                new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME);

        ResolveInfo info =
                getPackageManager().resolveActivity(
                        homeIntent,
                        PackageManager.MATCH_DEFAULT_ONLY
                );

        return info != null &&
                info.activityInfo != null &&
                getPackageName().equals(info.activityInfo.packageName);
    }

    private void requestHomeRole() {
        try {
            RoleManager roleManager = getSystemService(RoleManager.class);

            if (roleManager != null &&
                    roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {

                startActivity(
                        roleManager.createRequestRoleIntent(
                                RoleManager.ROLE_HOME
                        )
                );

                return;
            }

        } catch (Throwable ignored) {
        }

        try {
            startActivity(
                    new Intent(Settings.ACTION_HOME_SETTINGS)
            );

        } catch (Throwable throwable) {

            Toast.makeText(
                    this,
                    "Open Settings > Apps > Choose default apps > Home app",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {

        if (event.sensor.getType() != Sensor.TYPE_HINGE_ANGLE ||
                event.values.length == 0) {

            return;
        }

        long now =
                android.os.SystemClock.uptimeMillis();

        if (now - lastSensorFrameMs < 10) {
            return;
        }

        lastSensorFrameMs = now;

        float angle =
                clamp(
                        event.values[0],
                        0f,
                        180f
                );

        lastAngle = angle;

        if (mainSurface != null) {
            mainSurface.applyHinge(angle);
        }

        if (angle > ENDPOINT_CLOSED &&
                angle < ENDPOINT_OPEN) {

            cancelPendingDismiss();
            syncPresentations();

        } else {

            scheduleSecondaryDismiss();
        }

        for (DuoPresentation presentation :
                new ArrayList<>(presentations.values())) {

            presentation.applyHinge(angle);
        }
    }

    @Override
    public void onAccuracyChanged(
            Sensor sensor,
            int accuracy
    ) {
    }

    @Override
    public void onDisplayAdded(int displayId) {

        if (launcherVisible) {
            syncPresentations();
        }
    }

    @Override
    public void onDisplayRemoved(int displayId) {

        DuoPresentation presentation =
                presentations.remove(displayId);

        if (presentation != null) {
            safeDismiss(presentation);
        }
    }

    @Override
    public void onDisplayChanged(int displayId) {

        if (launcherVisible) {
            syncPresentations();
        }
    }

    private void syncPresentations() {

        if (!launcherVisible ||
                displayManager == null ||
                mainSurface == null) {

            return;
        }

        if (lastAngle <= ENDPOINT_CLOSED ||
                lastAngle >= ENDPOINT_OPEN) {

            return;
        }

        Display currentDisplay = getDisplay();

        int currentDisplayId =
                currentDisplay == null
                        ? Display.DEFAULT_DISPLAY
                        : currentDisplay.getDisplayId();

        Map<Integer, Display> candidates =
                new HashMap<>();

        /*
         * Some foldables expose built-in displays using this category.
         * It is accessed through the public DisplayManager API and does
         * not rely on hidden Display#getType or TYPE_INTERNAL APIs.
         */
        try {

            Display[] builtInDisplays =
                    displayManager.getDisplays(
                            BUILT_IN_DISPLAYS
                    );

            if (builtInDisplays != null) {

                for (Display display : builtInDisplays) {

                    if (display != null) {
                        candidates.put(
                                display.getDisplayId(),
                                display
                        );
                    }
                }
            }

        } catch (Throwable ignored) {
        }

        /*
         * Public presentation-display fallback.
         */
        try {

            Display[] presentationDisplays =
                    displayManager.getDisplays(
                            DisplayManager.DISPLAY_CATEGORY_PRESENTATION
                    );

            if (presentationDisplays != null) {

                for (Display display : presentationDisplays) {

                    if (display != null) {
                        candidates.put(
                                display.getDisplayId(),
                                display
                        );
                    }
                }
            }

        } catch (Throwable ignored) {
        }

        Set<Integer> keep =
                new HashSet<>();

        for (Display display : candidates.values()) {

            if (display == null) {
                continue;
            }

            if (display.getDisplayId() == currentDisplayId) {
                continue;
            }

            if (!display.isValid()) {
                continue;
            }

            keep.add(
                    display.getDisplayId()
            );

            if (!presentations.containsKey(
                    display.getDisplayId()
            )) {

                try {

                    DuoPresentation presentation =
                            new DuoPresentation(
                                    this,
                                    display
                            );

                    presentation.show();

                    presentations.put(
                            display.getDisplayId(),
                            presentation
                    );

                    presentation.applyHinge(
                            lastAngle
                    );

                } catch (Throwable ignored) {

                    /*
                     * Samsung ultimately decides whether the inactive
                     * internal display is available to Presentation.
                     */
                }
            }
        }

        for (Integer id :
                new HashSet<>(presentations.keySet())) {

            DuoPresentation presentation =
                    presentations.get(id);

            if (presentation == null) {
                presentations.remove(id);
                continue;
            }

            Display presentationDisplay =
                    presentation.getDisplay();

            if (presentationDisplay == null ||
                    !presentationDisplay.isValid()) {

                presentations.remove(id);
                safeDismiss(presentation);
            }
        }
    }

    private void scheduleSecondaryDismiss() {

        cancelPendingDismiss();

        dismissSecondaryRunnable = () -> {

            if (lastAngle <= ENDPOINT_CLOSED ||
                    lastAngle >= ENDPOINT_OPEN) {

                dismissAllPresentations();
            }
        };

        handler.postDelayed(
                dismissSecondaryRunnable,
                550
        );
    }

    private void cancelPendingDismiss() {

        if (dismissSecondaryRunnable != null) {

            handler.removeCallbacks(
                    dismissSecondaryRunnable
            );

            dismissSecondaryRunnable = null;
        }
    }

    private void dismissAllPresentations() {

        cancelPendingDismiss();

        for (DuoPresentation presentation :
                new ArrayList<>(presentations.values())) {

            safeDismiss(presentation);
        }

        presentations.clear();
    }

    private static void safeDismiss(
            Presentation presentation
    ) {

        try {
            presentation.dismiss();
        } catch (Throwable ignored) {
        }
    }

    private class DuoPresentation
            extends Presentation {

        private HomeSurface surface;

        DuoPresentation(
                Context outerContext,
                Display display
        ) {

            super(
                    outerContext,
                    display
            );
        }

        @Override
        protected void onCreate(
                Bundle savedInstanceState
        ) {

            super.onCreate(
                    savedInstanceState
            );

            Window window =
                    getWindow();

            if (window != null) {

                configureWindow(window);

                WindowManager.LayoutParams params =
                        window.getAttributes();

                params.dimAmount = 0f;

                window.setAttributes(params);
            }

            surface =
                    new HomeSurface(
                            getContext(),
                            isWideDisplay(getDisplay()),
                            true
                    );

            setContentView(surface);
        }

        void applyHinge(float angle) {

            if (surface != null) {
                surface.applyHinge(angle);
            }
        }
    }

    private static class HomeSurface
            extends FrameLayout {

        private final boolean wide;
        private final boolean presentation;

        private final FrameLayout visualLayer;

        private final int maxBlurPx;

        HomeSurface(
                Context context,
                boolean wide,
                boolean presentation
        ) {

            super(context);

            this.wide = wide;
            this.presentation = presentation;

            this.maxBlurPx =
                    dp(
                            context,
                            wide ? 76 : 62
                    );

            setBackgroundColor(
                    Color.TRANSPARENT
            );

            setClickable(true);
            setLongClickable(true);

            visualLayer =
                    new FrameLayout(context);

            visualLayer.setBackgroundColor(
                    Color.rgb(
                            18,
                            18,
                            22
                    )
            );

            visualLayer.setLayerType(
                    View.LAYER_TYPE_HARDWARE,
                    null
            );

            addView(
                    visualLayer,
                    new FrameLayout.LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            LayoutParams.MATCH_PARENT
                    )
            );

            addWallpaper(
                    context,
                    visualLayer
            );

            addApps(
                    context,
                    visualLayer,
                    wide
            );
        }

        private void addWallpaper(
                Context context,
                FrameLayout parent
        ) {

            ImageView wallpaper =
                    new ImageView(context);

            wallpaper.setScaleType(
                    ImageView.ScaleType.CENTER_CROP
            );

            try {

                Drawable drawable =
                        android.app.WallpaperManager
                                .getInstance(context)
                                .getDrawable();

                wallpaper.setImageDrawable(
                        drawable
                );

            } catch (Throwable ignored) {

                GradientDrawable background =
                        new GradientDrawable(
                                GradientDrawable.Orientation.TL_BR,
                                new int[]{
                                        Color.rgb(
                                                29,
                                                31,
                                                42
                                        ),
                                        Color.rgb(
                                                9,
                                                10,
                                                14
                                        )
                                }
                        );

                wallpaper.setImageDrawable(
                        background
                );
            }

            parent.addView(
                    wallpaper,
                    new FrameLayout.LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            LayoutParams.MATCH_PARENT
                    )
            );

            View scrim =
                    new View(context);

            scrim.setBackgroundColor(
                    Color.argb(
                            42,
                            0,
                            0,
                            0
                    )
            );

            parent.addView(
                    scrim,
                    new FrameLayout.LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            LayoutParams.MATCH_PARENT
                    )
            );
        }

        private void addApps(
                Context context,
                FrameLayout parent,
                boolean wide
        ) {

            ScrollView scroll =
                    new ScrollView(context);

            scroll.setFillViewport(true);
            scroll.setClipToPadding(false);

            GridLayout grid =
                    new GridLayout(context);

            int columns =
                    wide ? 7 : 4;

            grid.setColumnCount(columns);
            grid.setAlignmentMode(
                    GridLayout.ALIGN_BOUNDS
            );

            grid.setUseDefaultMargins(false);

            int horizontalPadding =
                    dp(
                            context,
                            wide ? 28 : 14
                    );

            grid.setPadding(
                    horizontalPadding,
                    dp(
                            context,
                            wide ? 72 : 58
                    ),
                    horizontalPadding,
                    dp(
                            context,
                            120
                    )
            );

            List<ResolveInfo> apps =
                    queryApps(context);

            int iconSize =
                    dp(
                            context,
                            wide ? 58 : 54
                    );

            int cellWidth =
                    dp(
                            context,
                            wide ? 112 : 86
                    );

            int cellHeight =
                    dp(
                            context,
                            wide ? 110 : 106
                    );

            for (ResolveInfo info : apps) {

                if (info.activityInfo == null) {
                    continue;
                }

                if (info.activityInfo.packageName.equals(
                        context.getPackageName()
                )) {
                    continue;
                }

                LinearLayout cell =
                        new LinearLayout(context);

                cell.setOrientation(
                        LinearLayout.VERTICAL
                );

                cell.setGravity(
                        Gravity.CENTER_HORIZONTAL
                );

                cell.setPadding(
                        dp(context, 4),
                        dp(context, 5),
                        dp(context, 4),
                        dp(context, 4)
                );

                ImageView icon =
                        new ImageView(context);

                try {

                    icon.setImageDrawable(
                            info.loadIcon(
                                    context.getPackageManager()
                            )
                    );

                } catch (Throwable ignored) {
                }

                LinearLayout.LayoutParams iconParams =
                        new LinearLayout.LayoutParams(
                                iconSize,
                                iconSize
                        );

                cell.addView(
                        icon,
                        iconParams
                );

                TextView label =
                        new TextView(context);

                CharSequence labelText;

                try {

                    labelText =
                            info.loadLabel(
                                    context.getPackageManager()
                            );

                } catch (Throwable throwable) {

                    labelText =
                            info.activityInfo.packageName;
                }

                label.setText(labelText);

                label.setTextSize(
                        wide ? 12 : 11
                );

                label.setTextColor(
                        Color.WHITE
                );

                label.setGravity(
                        Gravity.CENTER
                );

                label.setSingleLine(true);

                label.setEllipsize(
                        android.text.TextUtils
                                .TruncateAt.END
                );

                label.setShadowLayer(
                        3f,
                        0f,
                        1f,
                        Color.BLACK
                );

                LinearLayout.LayoutParams labelParams =
                        new LinearLayout.LayoutParams(
                                cellWidth - dp(context, 8),
                                LayoutParams.WRAP_CONTENT
                        );

                labelParams.topMargin =
                        dp(
                                context,
                                5
                        );

                cell.addView(
                        label,
                        labelParams
                );

                ComponentName component =
                        new ComponentName(
                                info.activityInfo.packageName,
                                info.activityInfo.name
                        );

                cell.setOnClickListener(
                        v -> launchComponent(
                                context,
                                component
                        )
                );

                GridLayout.LayoutParams gridParams =
                        new GridLayout.LayoutParams();

                gridParams.width =
                        cellWidth;

                gridParams.height =
                        cellHeight;

                gridParams.setGravity(
                        Gravity.CENTER
                );

                grid.addView(
                        cell,
                        gridParams
                );
            }

            scroll.addView(
                    grid,
                    new ScrollView.LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            LayoutParams.WRAP_CONTENT
                    )
            );

            parent.addView(
                    scroll,
                    new FrameLayout.LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            LayoutParams.MATCH_PARENT
                    )
            );

            TextView hint =
                    new TextView(context);

            hint.setText(
                    presentation
                            ? ""
                            : "Long-press anywhere for Fold Duo setup"
            );

            hint.setTextSize(11);

            hint.setTextColor(
                    Color.argb(
                            150,
                            255,
                            255,
                            255
                    )
            );

            hint.setGravity(
                    Gravity.CENTER
            );

            FrameLayout.LayoutParams hintParams =
                    new FrameLayout.LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            dp(context, 42),
                            Gravity.BOTTOM
                    );

            hintParams.bottomMargin =
                    dp(
                            context,
                            16
                    );

            parent.addView(
                    hint,
                    hintParams
            );
        }

        void applyHinge(float angle) {

            float progress =
                    clamp(
                            angle / 180f,
                            0f,
                            1f
                    );

            float middle =
                    (float) Math.pow(
                            Math.max(
                                    0f,
                                    Math.sin(
                                            Math.PI * progress
                                    )
                            ),
                            0.88
                    );

            float radius =
                    maxBlurPx * middle;

            if (radius > 0.75f) {

                visualLayer.setRenderEffect(
                        RenderEffect.createBlurEffect(
                                radius,
                                radius,
                                Shader.TileMode.CLAMP
                        )
                );

            } else {

                visualLayer.setRenderEffect(null);
            }

            /*
             * Keep the left edge anchored so the surface expands
             * toward the right during unfolding.
             */
            visualLayer.setPivotX(0f);

            visualLayer.setPivotY(
                    getHeight() > 0
                            ? getHeight() * 0.5f
                            : 0f
            );

            if (wide) {

                float reveal =
                        smoothStep(
                                0.10f,
                                0.90f,
                                progress
                        );

                visualLayer.setScaleX(
                        0.82f +
                                0.18f * reveal
                );

                visualLayer.setScaleY(
                        0.97f +
                                0.03f * reveal
                );

                visualLayer.setAlpha(
                        0.74f +
                                0.26f * reveal
                );

            } else {

                float stretch =
                        smoothStep(
                                0.04f,
                                0.72f,
                                progress
                        );

                visualLayer.setScaleX(
                        1.00f +
                                0.15f * stretch
                );

                visualLayer.setScaleY(
                        1.00f -
                                0.025f * middle
                );

                visualLayer.setAlpha(
                        1.00f -
                                0.10f *
                                        smoothStep(
                                                0.48f,
                                                0.88f,
                                                progress
                                        )
                );
            }
        }

        void pulseTransition() {

            android.animation.ValueAnimator animator =
                    android.animation.ValueAnimator.ofFloat(
                            0f,
                            1f,
                            0f
                    );

            animator.setDuration(360);

            animator.addUpdateListener(
                    valueAnimator -> {

                        float progress =
                                (Float)
                                        valueAnimator
                                                .getAnimatedValue();

                        float radius =
                                maxBlurPx *
                                        progress;

                        if (radius > 0.75f) {

                            visualLayer.setRenderEffect(
                                    RenderEffect.createBlurEffect(
                                            radius,
                                            radius,
                                            Shader.TileMode.CLAMP
                                    )
                            );

                        } else {

                            visualLayer.setRenderEffect(null);
                        }
                    }
            );

            animator.start();
        }
    }

    private static List<ResolveInfo> queryApps(
            Context context
    ) {

        PackageManager packageManager =
                context.getPackageManager();

        Intent query =
                new Intent(
                        Intent.ACTION_MAIN
                ).addCategory(
                        Intent.CATEGORY_LAUNCHER
                );

        List<ResolveInfo> result;

        try {

            result =
                    new ArrayList<>(
                            packageManager.queryIntentActivities(
                                    query,
                                    PackageManager.MATCH_ALL
                            )
                    );

        } catch (Throwable throwable) {

            result =
                    new ArrayList<>();
        }

        Collections.sort(
                result,
                Comparator.comparing(
                        resolveInfo -> {

                            try {

                                return resolveInfo
                                        .loadLabel(
                                                packageManager
                                        )
                                        .toString()
                                        .toLowerCase();

                            } catch (Throwable ignored) {

                                return resolveInfo.activityInfo == null
                                        ? ""
                                        : resolveInfo.activityInfo.packageName;
                            }
                        }
                )
        );

        return result;
    }

    private static void launchComponent(
            Context context,
            ComponentName component
    ) {

        try {

            Intent intent =
                    new Intent(
                            Intent.ACTION_MAIN
                    );

            intent.addCategory(
                    Intent.CATEGORY_LAUNCHER
            );

            intent.setComponent(
                    component
            );

            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            );

            context.startActivity(
                    intent
            );

        } catch (Throwable throwable) {

            Toast.makeText(
                    context,
                    "Could not open app",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private static boolean isWideDisplay(
            Display display
    ) {

        if (display == null) {
            return false;
        }

        try {

            Display.Mode mode =
                    display.getMode();

            float width =
                    mode.getPhysicalWidth();

            float height =
                    mode.getPhysicalHeight();

            float ratio =
                    Math.min(
                            width,
                            height
                    ) /
                            Math.max(
                                    width,
                                    height
                            );

            return ratio > 0.62f;

        } catch (Throwable ignored) {

            return false;
        }
    }

    private static float smoothStep(
            float edge0,
            float edge1,
            float value
    ) {

        float progress =
                clamp(
                        (value - edge0) /
                                (edge1 - edge0),
                        0f,
                        1f
                );

        return progress *
                progress *
                (3f - 2f * progress);
    }

    private static float clamp(
            float value,
            float minimum,
            float maximum
    ) {

        return Math.max(
                minimum,
                Math.min(
                        maximum,
                        value
                )
        );
    }

    private static LinearLayout.LayoutParams matchWrap() {

        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private static int dp(
            Context context,
            int value
    ) {

        return Math.round(
                value *
                        context
                                .getResources()
                                .getDisplayMetrics()
                                .density
        );
    }
}
