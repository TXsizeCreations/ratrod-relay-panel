package com.ratrod.panel;

import android.content.Context;
import android.webkit.JavascriptInterface;

/**
 * The surface the web UI sees inside the app: window.RatRodNative.
 * Every method returns a JSON string so the page never has to guess.
 */
public class Bridge {

    private static final String PREFS = "ratrod_ui";

    private final RelayLink link;
    private final Context ctx;

    public Bridge(RelayLink link, Context ctx) {
        this.link = link;
        this.ctx = ctx;
    }

    /**
     * Small key/value store for UI settings (button names). Kept in
     * SharedPreferences so it cannot depend on the WebView allowing
     * localStorage for the file:// origin.
     */
    @JavascriptInterface
    public String storeGet(String key) {
        if (key == null || key.isEmpty()) return "";
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "");
    }

    @JavascriptInterface
    public void storeSet(String key, String value) {
        if (key == null || key.isEmpty()) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit()
           .putString(key, value == null ? "" : value)
           .apply();
    }

    @JavascriptInterface
    public String status() {
        return link.statusJson();
    }

    @JavascriptInterface
    public String connect() {
        link.tryOpen();
        return link.statusJson();
    }

    @JavascriptInterface
    public String relay(int ch, int on) {
        if (ch < 1 || ch > 8) return RelayLink.errorJson("channel");
        if (!link.mcuAlive()) return RelayLink.errorJson("no-mcu");
        link.setRelay(ch, on != 0);
        return link.statusJson();
    }

    @JavascriptInterface
    public String allOff() {
        link.allOff();
        return link.statusJson();
    }

    @JavascriptInterface
    public String disconnect() {
        link.close();
        return link.statusJson();
    }
}
