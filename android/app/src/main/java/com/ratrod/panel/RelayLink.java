package com.ratrod.panel;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the USB serial link to the Arduino Uno.
 *
 * Frames are plain newline terminated ASCII, identical to the protocol in
 * firmware/uno_relay_controller:
 *
 *   -> S            request state
 *   -> K            heartbeat (Uno cuts non-essential relays after 3 s of silence)
 *   -> R<ch>:<0|1>  set relay 1..8
 *   -> X            all off
 *
 *   <- S:01010000   8 bit state, channel 1 first
 *   <- A:<ch>:<0|1> acknowledgement
 */
public class RelayLink implements SerialInputOutputManager.Listener {

    static final String TAG = "RelayLink";
    static final String ACTION_PERMISSION = "com.ratrod.panel.USB_PERMISSION";

    private static final int BAUD = 115200;
    private static final long MCU_TIMEOUT_MS = 4000;

    private final Context ctx;
    private final UsbManager usb;

    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private ScheduledExecutorService ticker;

    private final StringBuilder rx = new StringBuilder();
    private final int[] state = new int[8];

    private UsbSerialPort port;
    private SerialInputOutputManager io;
    private volatile boolean open = false;
    private volatile long lastMcu = 0;

    public RelayLink(Context context) {
        this.ctx = context.getApplicationContext();
        this.usb = (UsbManager) this.ctx.getSystemService(Context.USB_SERVICE);
    }

    // ---------------------------------------------------------------- status

    public boolean isOpen() {
        return open;
    }

    public boolean mcuAlive() {
        return open && (System.currentTimeMillis() - lastMcu) < MCU_TIMEOUT_MS;
    }

    public String statusJson() {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"ch\":[");
        for (int i = 0; i < 8; i++) {
            if (i > 0) sb.append(',');
            sb.append(state[i]);
        }
        sb.append("],\"link\":").append(mcuAlive());
        sb.append(",\"usb\":").append(open);
        sb.append('}');
        return sb.toString();
    }

    public static String errorJson(String what) {
        return "{\"error\":\"" + what + "\"}";
    }

    // ---------------------------------------------------------------- connect

    /** Idempotent: opens the port, or asks for permission, or quietly does nothing. */
    public boolean tryOpen() {
        if (open) return true;
        if (usb == null) return false;

        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb);
        if (drivers.isEmpty()) return false;

        UsbSerialDriver driver = drivers.get(0);
        UsbDevice device = driver.getDevice();

        if (!usb.hasPermission(device)) {
            usb.requestPermission(device, permissionIntent());
            return false;
        }

        UsbDeviceConnection conn = usb.openDevice(device);
        if (conn == null) {
            Log.w(TAG, "openDevice returned null");
            return false;
        }

        try {
            UsbSerialPort p = driver.getPorts().get(0);
            p.open(conn);
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            try {
                // DTR high is what the Uno's USB bridge expects; note that
                // asserting it resets the ATmega, so the board reboots (all
                // relays off) the instant we connect.
                p.setDTR(true);
                p.setRTS(true);
            } catch (Exception ignored) {
            }
            port = p;
        } catch (IOException e) {
            Log.w(TAG, "port open failed", e);
            close();
            return false;
        }

        io = new SerialInputOutputManager(port, this);
        io.start();

        open = true;
        lastMcu = System.currentTimeMillis();
        write("S\n");
        startTicker();
        Log.i(TAG, "connected to " + device.getDeviceName());
        return true;
    }

    private PendingIntent permissionIntent() {
        Intent i = new Intent(ACTION_PERMISSION).setPackage(ctx.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(ctx, 0, i, flags);
    }

    private void startTicker() {
        stopTicker();
        ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(() -> write("K\n"), 500, 700, TimeUnit.MILLISECONDS);
    }

    private void stopTicker() {
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
    }

    public void close() {
        open = false;
        stopTicker();
        if (io != null) {
            try {
                io.stop();
            } catch (Exception ignored) {
            }
            io = null;
        }
        if (port != null) {
            try {
                port.close();
            } catch (IOException ignored) {
            }
            port = null;
        }
        synchronized (rx) {
            rx.setLength(0);
        }
    }

    // ---------------------------------------------------------------- commands

    public void setRelay(int ch, boolean on) {
        if (ch < 1 || ch > 8) return;
        write("R" + ch + ":" + (on ? 1 : 0) + "\n");
    }

    public void allOff() {
        write("X\n");
    }

    private void write(final String s) {
        if (!open || port == null) return;
        writer.execute(() -> {
            UsbSerialPort p = port;
            if (p == null) return;
            try {
                p.write(s.getBytes(StandardCharsets.US_ASCII), 300);
            } catch (IOException e) {
                Log.w(TAG, "write failed", e);
            }
        });
    }

    // ---------------------------------------------------------------- reading

    @Override
    public void onNewData(byte[] data) {
        synchronized (rx) {
            for (byte b : data) {
                char c = (char) (b & 0xFF);
                if (c == '\n') {
                    handleLine(rx.toString());
                    rx.setLength(0);
                } else if (c != '\r' && rx.length() < 64) {
                    rx.append(c);
                }
            }
        }
    }

    private void handleLine(String line) {
        if (line.isEmpty()) return;
        lastMcu = System.currentTimeMillis();

        if (line.startsWith("S:") && line.length() >= 10) {
            for (int i = 0; i < 8; i++) {
                state[i] = line.charAt(i + 2) == '1' ? 1 : 0;
            }
        } else if (line.startsWith("A:")) {
            String[] parts = line.split(":");
            if (parts.length == 3) {
                try {
                    int ch = Integer.parseInt(parts[1].trim());
                    int v = Integer.parseInt(parts[2].trim());
                    if (ch >= 1 && ch <= 8) state[ch - 1] = v != 0 ? 1 : 0;
                } catch (NumberFormatException ignored) {
                }
            }
        } else if (line.startsWith("W:")) {
            Log.w(TAG, "uno warning: " + line);
        }
    }

    @Override
    public void onRunError(Exception e) {
        Log.w(TAG, "io thread died", e);
        close();
    }
}
