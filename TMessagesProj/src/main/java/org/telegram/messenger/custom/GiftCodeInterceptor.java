package org.telegram.messenger.custom;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;

public class GiftCodeInterceptor {
    public static final int MODE_NORMAL = 0;
    public static final int MODE_SERIES = 1;
    public static final int MODE_BOTTOM = 2;

    private static final String PREFS_NAME = "tg_fastlane_prefs";
    private static final String KEY_TARGETED_CHANNELS = "targeted_channels";
    private static final String KEY_MASTER_ENABLED = "master_enabled";
    private static final String KEY_DISPATCH_MODE = "dispatch_mode";
    private static final String KEY_SERIES_INTERVAL = "series_interval_ms";

    private static final Pattern EXACT_32_PATTERN = Pattern.compile("\\b[A-Za-z0-9]{32}\\b");
    private static final Pattern FALLBACK_PATTERN = Pattern.compile("\\b[A-Za-z0-9]{16,31}\\b");

    private static final ExecutorService RELAY_EXECUTOR = Executors.newFixedThreadPool(4);
    private static final ScheduledExecutorService SERIES_SCHEDULER = Executors.newSingleThreadScheduledExecutor();

    // DIRECT DUAL RELAY ENDPOINTS
    private static final String[] DIRECT_RELAY_ENDPOINTS = {
        "https://srv1598644.hstgr.cloud:3001/tg/codes",
        "https://t2.autoclaimer.in"
    };

    private static final Set<Long> TARGETED_CHANNELS = new HashSet<>();
    private static boolean masterEnabled = true;
    private static int dispatchMode = MODE_NORMAL;
    private static int seriesIntervalMs = 1000;
    private static boolean prefsLoaded = false;
    private static String lastDispatchedCode = "";

    private static synchronized void ensurePrefsLoaded() {
        if (prefsLoaded) return;
        Context context = ApplicationLoader.applicationContext;
        if (context != null) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            masterEnabled = prefs.getBoolean(KEY_MASTER_ENABLED, true);
            dispatchMode = prefs.getInt(KEY_DISPATCH_MODE, MODE_NORMAL);
            seriesIntervalMs = prefs.getInt(KEY_SERIES_INTERVAL, 1000);
            Set<String> set = prefs.getStringSet(KEY_TARGETED_CHANNELS, null);
            if (set != null) {
                TARGETED_CHANNELS.clear();
                for (String s : set) {
                    try {
                        TARGETED_CHANNELS.add(Long.parseLong(s));
                    } catch (Exception ignored) {}
                }
            }
            prefsLoaded = true;
        }
    }

    public static synchronized boolean isChannelTargeted(long channelId) {
        ensurePrefsLoaded();
        if (!masterEnabled) return false;
        long cleanId = Math.abs(channelId);
        return TARGETED_CHANNELS.contains(cleanId);
    }

    public static synchronized void toggleChannelTargeted(long channelId, boolean enable) {
        ensurePrefsLoaded();
        long cleanId = Math.abs(channelId);
        if (enable) {
            TARGETED_CHANNELS.add(cleanId);
        } else {
            TARGETED_CHANNELS.remove(cleanId);
        }
        Context context = ApplicationLoader.applicationContext;
        if (context != null) {
            SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
            Set<String> set = new HashSet<>();
            for (Long id : TARGETED_CHANNELS) set.add(String.valueOf(id));
            editor.putStringSet(KEY_TARGETED_CHANNELS, set);
            editor.apply();
        }
    }

    public static synchronized void setDispatchMode(int mode) {
        dispatchMode = mode;
        Context context = ApplicationLoader.applicationContext;
        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                   .edit().putInt(KEY_DISPATCH_MODE, mode).apply();
        }
    }

    public static synchronized int getDispatchMode() {
        ensurePrefsLoaded();
        return dispatchMode;
    }

    public static synchronized void setSeriesIntervalMs(int ms) {
        seriesIntervalMs = Math.max(0, Math.min(4000, ms));
        Context context = ApplicationLoader.applicationContext;
        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                   .edit().putInt(KEY_SERIES_INTERVAL, seriesIntervalMs).apply();
        }
    }

    public static synchronized int getSeriesIntervalMs() {
        ensurePrefsLoaded();
        return seriesIntervalMs;
    }

    public static synchronized void setMasterEnabled(boolean enabled) {
        masterEnabled = enabled;
        Context context = ApplicationLoader.applicationContext;
        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                   .edit().putBoolean(KEY_MASTER_ENABLED, enabled).apply();
        }
    }

    public static synchronized boolean isMasterEnabled() {
        ensurePrefsLoaded();
        return masterEnabled;
    }

    public static void inspectUpdate(int currentAccount, TLRPC.Update update) {
        TLRPC.Message msg = null;
        if (update instanceof TLRPC.TL_updateNewChannelMessage) {
            msg = ((TLRPC.TL_updateNewChannelMessage) update).message;
        } else if (update instanceof TLRPC.TL_updateEditChannelMessage) {
            msg = ((TLRPC.TL_updateEditChannelMessage) update).message;
        }
        if (msg == null || msg.peer_id == null) return;

        long channelId = msg.peer_id.channel_id;
        if (channelId == 0) return;

        // 1. Channel-Specific Guard
        if (!isChannelTargeted(channelId)) return;

        // 2. Zero OCR Media Bypass: skip photos, stickers, albums, voice notes if no text
        if (msg.media != null && !(msg.media instanceof TLRPC.TL_messageMediaEmpty)) {
            if (msg.message == null || msg.message.trim().isEmpty()) {
                return;
            }
        }

        // 3. Scan plain message text
        if (msg.message != null && msg.message.length() >= 16) {
            scanAndDispatch(currentAccount, channelId, msg.id, msg.message);
        }

        // 4. Scan hidden URL entities
        if (msg.entities != null) {
            for (int i = 0, size = msg.entities.size(); i < size; i++) {
                TLRPC.MessageEntity entity = msg.entities.get(i);
                if (entity instanceof TLRPC.TL_messageEntityTextUrl && entity.url != null) {
                    scanAndDispatch(currentAccount, channelId, msg.id, entity.url);
                }
            }
        }

        // 5. Scan reply markup inline keyboard buttons if present
        if (msg.reply_markup instanceof TLRPC.TL_replyInlineMarkup) {
            TLRPC.TL_replyInlineMarkup inline = (TLRPC.TL_replyInlineMarkup) msg.reply_markup;
            for (TLRPC.TL_keyboardButtonRow row : inline.rows) {
                for (TLRPC.KeyboardButton btn : row.buttons) {
                    if (btn instanceof TLRPC.TL_keyboardButtonUrl && ((TLRPC.TL_keyboardButtonUrl) btn).url != null) {
                        scanAndDispatch(currentAccount, channelId, msg.id, ((TLRPC.TL_keyboardButtonUrl) btn).url);
                    }
                    if (btn.text != null && btn.text.length() >= 16) {
                        scanAndDispatch(currentAccount, channelId, msg.id, btn.text);
                    }
                }
            }
        }
    }

    private static void scanAndDispatch(int currentAccount, long channelId, int msgId, String text) {
        List<String> codes = extractAllCodes(text);
        if (codes.isEmpty()) return;

        // Fire real MTProto reaction on the channel message (< 5ms)
        AutoReactionController.getInstance(currentAccount).dispatchRandomAllowedReaction(channelId, msgId);

        ensurePrefsLoaded();
        if (dispatchMode == MODE_BOTTOM) {
            // Prioritize bottom (last) code of the batch
            String bottomCode = codes.get(codes.size() - 1);
            triggerCapture(currentAccount, channelId, msgId, bottomCode);
        } else if (dispatchMode == MODE_SERIES && codes.size() > 1) {
            // Series mode: schedule codes spaced by seriesIntervalMs (0-4000ms slider)
            for (int i = 0; i < codes.size(); i++) {
                final String code = codes.get(i);
                long delay = (long) i * seriesIntervalMs;
                if (delay == 0) {
                    triggerCapture(currentAccount, channelId, msgId, code);
                } else {
                    SERIES_SCHEDULER.schedule(() -> {
                        triggerCapture(currentAccount, channelId, msgId, code);
                    }, delay, TimeUnit.MILLISECONDS);
                }
            }
        } else {
            // Normal mode: dispatch all codes immediately
            for (String code : codes) {
                triggerCapture(currentAccount, channelId, msgId, code);
            }
        }
    }

    private static List<String> extractAllCodes(String text) {
        List<String> result = new ArrayList<>();
        // Priority 1: Exact 32-character code
        Matcher m32 = EXACT_32_PATTERN.matcher(text);
        while (m32.find()) {
            String candidate = m32.group();
            if (isLikelyCode(candidate) && !result.contains(candidate)) {
                result.add(candidate);
            }
        }
        if (!result.isEmpty()) return result;

        // Priority 2: Fallback 16-31 character codes
        Matcher mFallback = FALLBACK_PATTERN.matcher(text);
        while (mFallback.find()) {
            String candidate = mFallback.group();
            if (isLikelyCode(candidate) && !result.contains(candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static boolean isLikelyCode(String s) {
        if (s.equalsIgnoreCase("telegram") || s.equalsIgnoreCase("autoclaimer")) return false;
        boolean hasDigit = false;
        boolean hasLetter = false;
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') hasDigit = true;
            else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) hasLetter = true;
            if (hasDigit && hasLetter) return true;
        }
        return s.length() == 32;
    }

    private static synchronized void triggerCapture(int currentAccount, long channelId, int msgId, String code) {
        if (code.equals(lastDispatchedCode)) return;
        lastDispatchedCode = code;
        long ts = System.currentTimeMillis();

        // 1. Direct Foreground IPC Broadcast to GC Telegram Edition
        dispatchToLocalClaimer(code, channelId, ts);

        // 2. Direct Dual Relays to both endpoints concurrently
        relayToServers(code, channelId, ts);
    }

    private static void dispatchToLocalClaimer(String code, long channelId, long ts) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) return;

        Intent intent = new Intent("com.tg.tgnative.ACTION_LOCAL_CODE");
        intent.setPackage("com.rajuofficial.gctgedition");
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        intent.putExtra("code", code);
        intent.putExtra("channel_id", channelId);
        intent.putExtra("timestamp", ts);
        context.sendBroadcast(intent);
    }

    private static void relayToServers(String code, long channelId, long ts) {
        for (final String endpoint : DIRECT_RELAY_ENDPOINTS) {
            RELAY_EXECUTOR.execute(() -> {
                try {
                    URL url = new URL(endpoint);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Connection", "Keep-Alive");
                    conn.setConnectTimeout(1500);
                    conn.setReadTimeout(1500);
                    conn.setDoOutput(true);

                    String payload = String.format(
                        "{\"code\":\"%s\",\"channelId\":\"%d\",\"ts\":%d,\"source\":\"telegram_fastlane\"}",
                        code, channelId, ts
                    );
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(payload.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }
                    int resp = conn.getResponseCode();
                    FileLog.d("GiftCodeInterceptor: " + endpoint + " responded " + resp);
                } catch (Exception e) {
                    FileLog.e("GiftCodeInterceptor: error sending to " + endpoint, e);
                }
            });
        }
    }
}
