package com.wuguo.chicken;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.util.function.Consumer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 全屏横屏外壳：整个游戏都由 GLSurfaceView + GLES2 绘制。
 * 触摸事件统一投递到 GL 线程，避免和渲染线程抢状态。
 */
public final class MainActivity extends Activity implements GLSurfaceView.Renderer, Game.DialogHost {
    private GLSurfaceView view;
    private final Gl gl = new Gl();
    private final Game game = new Game();
    private long lastNs;
    private float acc;
    private int fpsFrames;
    private long fpsStartNs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Assets.init(this);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        game.setGl(gl);
        game.setDialogHost(this);
        game.init(this);

        view = new GLSurfaceView(this);
        view.setEGLContextClientVersion(2);
        view.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        view.setRenderer(this);
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        view.setOnTouchListener((v, e) -> {
            // 多指：每根手指各自带 pointerId 投递，POINTER_DOWN/POINTER_UP 也翻译成 DOWN/UP，
            // 这样「按住 ▶ 同时点 ▲」是两路独立事件（以前只取 index 0，第二根手指整个被丢掉）
            final int masked = e.getActionMasked();
            switch (masked) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    final int idx = e.getActionIndex();
                    final int id = e.getPointerId(idx);
                    final float x = Design.toDesignX(e.getX(idx));
                    final float y = Design.toDesignY(e.getY(idx));
                    final int act = masked == MotionEvent.ACTION_POINTER_DOWN ? MotionEvent.ACTION_DOWN
                            : (masked == MotionEvent.ACTION_POINTER_UP ? MotionEvent.ACTION_UP : masked);
                    view.queueEvent(() -> game.onTouch(act, id, x, y));
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    final int n = e.getPointerCount();
                    for (int i = 0; i < n; i++) {
                        final int id = e.getPointerId(i);
                        final float x = Design.toDesignX(e.getX(i));
                        final float y = Design.toDesignY(e.getY(i));
                        view.queueEvent(() -> game.onTouch(MotionEvent.ACTION_MOVE, id, x, y));
                    }
                    break;
                }
                case MotionEvent.ACTION_CANCEL: {
                    view.queueEvent(() -> game.onTouch(MotionEvent.ACTION_CANCEL, -1, 0f, 0f));
                    break;
                }
                default:
                    break;
            }
            return true;
        });

        FrameLayout root = new FrameLayout(this);
        root.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        hideBars();
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        if (view != null) view.onResume();
        view.queueEvent(game::onPauseApp);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (view != null) view.onPause();
    }

    // ---------- GLSurfaceView.Renderer ----------
    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        gl.onSurfaceCreated();
        Game.GlInfo.vendor = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VENDOR);
        Game.GlInfo.renderer = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER);
        Game.GlInfo.version = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VERSION);
        Game.GlInfo.glsl = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_SHADING_LANGUAGE_VERSION);
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        gl.onSurfaceChanged(w, h);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = System.nanoTime();
        if (lastNs == 0) lastNs = now;
        float dt = Math.min(0.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        acc += dt;
        int guard = 0;
        while (acc >= 1f / 60f && guard < 5) {
            game.stepWorld();
            acc -= 1f / 60f;
            guard++;
        }
        if (acc > 0.1f) acc = 0f;
        game.update(dt);
        game.draw();

        fpsFrames++;
        long t = System.nanoTime();
        if (fpsStartNs == 0) fpsStartNs = t;
        else if (t - fpsStartNs >= 1000000000L) {
            float fps = fpsFrames * 1e9f / (t - fpsStartNs);
            android.util.Log.i("wuguo-fps", String.format(java.util.Locale.US, "%.1f", fps));
            fpsFrames = 0;
            fpsStartNs = t;
        }
    }

    // ---------- 数值输入框 ----------
    @Override
    public void askNumber(String title, String initial, boolean integer, boolean decimalAllowed,
                          Consumer<String> onOk) {
        runOnUiThread(() -> {
            EditText et = new EditText(this);
            et.setText(initial);
            et.setSelectAllOnFocus(true);
            et.setTextColor(Color.WHITE);
            et.setTextSize(20);
            et.setTypeface(Typeface.MONOSPACE);
            et.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | InputType.TYPE_NUMBER_FLAG_SIGNED);
            int pad = (int) (24 * getResources().getDisplayMetrics().density);
            FrameLayout wrap = new FrameLayout(this);
            wrap.setPadding(pad, pad / 2, pad, 0);
            wrap.addView(et);
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setView(wrap)
                    .setPositiveButton("确定", (d, w) -> onOk.accept(et.getText().toString()))
                    .setNegativeButton("取消", null)
                    .show();
        });
    }
}
