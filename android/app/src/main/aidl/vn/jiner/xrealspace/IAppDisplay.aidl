package vn.jiner.xrealspace;

import android.view.Surface;

/** Runs in a Shizuku user service (shell uid): virtual displays for other apps + input injection. */
interface IAppDisplay {
    /** Shizuku calls this transaction to stop the service */
    void destroy() = 16777114;

    /** a new display that draws into [surface]; returns its display id (or -1) */
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void resizeDisplay(int displayId, int width, int height, int dpi) = 2;
    void releaseDisplay(int displayId) = 3;
    /** start an activity ("pkg/.Activity") on a display; returns the shell output */
    String launch(String component, int displayId) = 4;
    void tap(int displayId, float x, float y) = 5;
    void swipe(int displayId, float x1, float y1, float x2, float y2, int durationMs) = 6;
    void key(int displayId, int keyCode) = 7;
    /** text into the focused field of that display */
    void text(int displayId, String value) = 8;
}
