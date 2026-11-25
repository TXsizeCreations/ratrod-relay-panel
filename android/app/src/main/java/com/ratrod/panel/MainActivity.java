package com.ratrod.panel;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    private WebView web;
    private RelayLink link;
    private BroadcastReceiver usbRx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // dash use: the screen must not sleep while the panel is open
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        link = new RelayLink(this);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setBackgroundColor(0xFF0A0A0B);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(link, this), "RatRodNative");

        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        usbRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (RelayLink.ACTION_PERMISSION.equals(action)) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        link.tryOpen();
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    link.tryOpen();
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    link.close();
                }
            }
        };
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            link.tryOpen();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(RelayLink.ACTION_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbRx, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbRx, f);
        }
        link.tryOpen();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(usbRx);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        // Leave the relays exactly as they are; the Uno holds them and its own
        // watchdog cuts everything non-essential if the cable goes away.
        link.close();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
