package local.airuize.diplaylegacy;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Minimal API 17 host screen. Holds the CarPlay video SurfaceView and hands the surface plus touch
 * events to {@link LegacyCarPlayHost}, which drives the shared CarPlay stack.
 */
public class LegacyCarPlayActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "LegacyCarPlayActivity";
    private SurfaceView surfaceView;
    private TextView status;
    private boolean started = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private FrameLayout buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setText("CarPlay host ready");
        status.setTextColor(Color.WHITE);
        status.setTextSize(18f);
        int pad = Math.round(24f * getResources().getDisplayMetrics().density);
        status.setPadding(pad, pad, pad, pad);
        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private void startHost(SurfaceHolder holder) {
        if (started) return;
        Rect frame = holder.getSurfaceFrame();
        int width = frame.width();
        int height = frame.height();
        if (width <= 0 || height <= 0) return;
        status.setText("Starting CarPlay...");
        try {
            LegacyWirelessHost.start(this, holder.getSurface(), width, height);
            started = true;
        } catch (Throwable t) {
            Log.e(TAG, "carplay start failed", t);
            String detail = t.toString();
            Throwable c = t.getCause();
            while (c != null) {
                detail += "\n" + c.toString();
                c = c.getCause();
            }
            status.setTextSize(12f);
            status.setText("Start failed: " + detail);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (started && LegacyWirelessHost.sendTouch(event)) {
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "surface created");
        startHost(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surface changed: " + width + "x" + height);
        startHost(holder);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "surface destroyed");
    }

    @Override
    protected void onDestroy() {
        LegacyWirelessHost.shutdown();
        started = false;
        super.onDestroy();
    }
}