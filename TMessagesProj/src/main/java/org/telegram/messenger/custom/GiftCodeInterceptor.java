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
    private static final String KEY_AUTO_REACTION = "auto_reaction_enabled";
    private static final String KEY_CONTINUOUS_REACTION = "continuous_reaction_enabled";
    private static final String KEY_POLL_INTERVAL = "reaction_poll_interval_ms";

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
    private static boolean autoReactionEnabled = true;
    private static boolean continuousReactionEnabled = true;
    private static int dispatchMode = MODE_NORMAL;
    private static int seriesIntervalMs = 1000;
    private static int pollIntervalMs = 1000;
    private static boolean prefsLoaded = false;
    private static String lastDispatchedCode = "";

    private static synchronized void ensurePrefsLoaded() {
        if (prefsLoaded) return;
        Context context = ApplicationLoader.applicationContext;
        if (context != null) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            masterEnabled = prefs.getBoolean(KEY_MASTER_ENABLED, true);
            autoReactionEnabled = prefs.getBoolean(KEY_AUTO_REACTION, true);
            continuousReactionEnabled = prefs.getBoolean(KEY_CONTINUOUS_REACTION, true);
            dispatchMode = prefs.getInt(KEY_DISPATCH_MODE, MODE_NORMAL);
            seriesIntervalMs = prefs.getInt(KEY_SERIES_INTERVAL, 1000);
            pollIntervalMs = prefs.getInt(KEY_POLL_INTERVAL, 1000);
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

    public static synchronized void setContinuousReactionEnabled(boolean enabled) {
        continuousReactionEnabled = enabled;
        Context context = ApplicationLoader.applicationContext;
        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                   .edit().putBoolean(KEY_CONTINUOUS_REACTION, enabled).apply();
        }
    }

    public static synchronized boolean isContinuousReactionEnabled() {
        ensurePrefsLoaded();
        return continuousReactionEnabled;
    }

    public static synchronized void setPollIntervalMs(int ms) {
        pollIntervalMs = Math.max(500, Math.min(2000, ms));
        Context context = ApplicationLoader.applicationContext;
        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                   .edit().putInt(KEY_POLL_INTERVAL, pollIntervalMs).apply();
        }
    }

    public static synchronized int getPollIntervalMs() {
        ensurePrefsLoaded();
        return pollIntervalMs;
    }

    public static synchronized void setAutoReactionEnabled(boolean enabled) {
        autoReactionEnabled = enabled;
        Context context = ApplicationLoader.applicationContext;
        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                   .edit().putBoolean(KEY_AUTO_REACTION, enabled).apply();
        }
    }

    public static synchronized boolean isAutoReactionEnabled() {
        ensurePrefsLoaded();
        return autoReactionEnabled;
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
        } else if (update instanceof TLRPC.TL_updateNewMessage) {
            msg = ((TLRPC.TL_updateNewMessage) update).message;
        } else if (update instanceof TLRPC.TL_updateEditMessage) {
            msg = ((TLRPC.TL_updateEditMessage) update).message;
        }
        if (msg == null || msg.peer_id == null) return;

        long targetChatId = 0;
        long dialogId = 0;
        if (msg.peer_id instanceof TLRPC.TL_peerChannel) {
            targetChatId = msg.peer_id.channel_id;
            dialogId = -msg.peer_id.channel_id;
        } else if (msg.peer_id instanceof TLRPC.TL_peerChat) {
            targetChatId = msg.peer_id.chat_id;
            dialogId = -msg.peer_id.chat_id;
        } else if (msg.peer_id instanceof TLRPC.TL_peerUser) {
            targetChatId = msg.peer_id.user_id;
            dialogId = msg.peer_id.user_id;
        }
        if (targetChatId == 0) return;

        // 1. Channel/Group Whitelist Guard
        if (!isChannelTargeted(targetChatId) && !isChannelTargeted(Math.abs(dialogId))) return;

        // 2. Seamlessly update latest message in continuous auto-reaction controller
        if (msg.id > 0) {
            AutoReactionController.getInstance(currentAccount).updateLatestMessage(dialogId, msg.id);
        }

        // 3. Zero OCR Media Bypass: skip photos, stickers, albums, voice notes if no text
        if (msg.media != null && !(msg.media instanceof TLRPC.TL_messageMediaEmpty)) {
            if (msg.message == null || msg.message.trim().isEmpty()) {
                return;
            }
        }

        // 4. Scan plain message text
        if (msg.message != null && msg.message.length() >= 16) {
            scanAndDispatch(currentAccount, dialogId, targetChatId, msg.id, msg.message);
        }

        // 5. Scan inline keyboard buttons (Instant drop buttons)
        if (msg.reply_markup instanceof TLRPC.TL_replyInlineMarkup) {
            TLRPC.TL_replyInlineMarkup markup = (TLRPC.TL_replyInlineMarkup) msg.reply_markup;
            for (TLRPC.TL_keyboardButtonRow row : markup.rows) {
                for (TLRPC.KeyboardButton btn : row.buttons) {
                    if (btn instanceof TLRPC.TL_keyboardButtonUrl) {
                        String url = ((TLRPC.TL_keyboardButtonUrl) btn).url;
                        if (url != null && url.length() >= 16) {
                            scanAndDispatch(currentAccount, dialogId, targetChatId, msg.id, url);
                        }
                    }
                    if (btn.text != null && btn.text.length() >= 16) {
                        scanAndDispatch(currentAccount, dialogId, targetChatId, msg.id, btn.text);
                    }
                }
            }
        }
    }

    private static void scanAndDispatch(int currentAccount, long dialogId, long targetChatId, int msgId, String text) {
        List<String> codes = extractAllCodes(text);
        if (codes.isEmpty()) return;

        // Ensure reaction is dispatched immediately for captured gift code message
        AutoReactionController.getInstance(currentAccount).dispatchRandomAllowedReaction(dialogId, targetChatId, msgId);

        ensurePrefsLoaded();
        if (dispatchMode == MODE_BOTTOM) {
            String bottomCode = codes.get(codes.size() - 1);
            triggerCapture(currentAccount, dialogId, msgId, bottomCode);
        } else if (dispatchMode == MODE_SERIES && codes.size() > 1) {
            for (int i = 0; i < codes.size(); i++) {
                final String code = codes.get(i);
                long delay = (long) i * seriesIntervalMs;
                if (delay == 0) {
                    triggerCapture(currentAccount, dialogId, msgId, code);
                } else {
                    SERIES_SCHEDULER.schedule(() -> {
                        triggerCapture(currentAccount, dialogId, msgId, code);
                    }, delay, TimeUnit.MILLISECONDS);
                }
            }
        } else {
            for (String code : codes) {
                triggerCapture(currentAccount, dialogId, msgId, code);
            }
        }
    }

    public static List<String> extractAllCodes(String text) {
        if (text == null || text.length() < 16) return Collections.emptyList();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        Matcher m32 = EXACT_32_PATTERN.matcher(text);
        while (m32.find()) {
            set.add(m32.group());
        }
        if (set.isEmpty()) {
            Matcher mFallback = FALLBACK_PATTERN.matcher(text);
            while (mFallback.find()) {
                set.add(mFallback.group());
            }
        }
        return new ArrayList<>(set);
    }

    private static void triggerCapture(int currentAccount, long dialogId, int msgId, String code) {
        if (code == null || code.isEmpty()) return;
        synchronized (GiftCodeInterceptor.class) {
            if (code.equals(lastDispatchedCode)) return;
            lastDispatchedCode = code;
        }

        FileLog.d("TelegramFastLane: SNIPED CODE -> " + code + " in dialog " + dialogId);

        // 1. Concurrent Direct Dual Relay to HTTP Endpoints
        for (String endpoint : DIRECT_RELAY_ENDPOINTS) {
            RELAY_EXECUTOR.execute(() -> sendDirectHttpRelay(endpoint, code, dialogId, msgId));
        }

        // 2. High-Priority Local Foreground IPC Broadcast to GC Telegram Edition
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) {
                Intent intent = new Intent("com.tg.tgnative.ACTION_LOCAL_CODE");
                intent.setPackage("com.rajuofficial.gctgedition");
                intent.putExtra("code", code);
                intent.putExtra("channel_id", dialogId);
                intent.putExtra("msg_id", msgId);
                intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                ctx.sendBroadcast(intent);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void sendDirectHttpRelay(String endpointUrl, String code, long dialogId, int msgId) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpointUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Connection", "Keep-Alive");
            conn.setRequestProperty("User-Agent", "TelegramFastLane/2.0");

            String payload = "{\"code\":\"" + code + "\",\"dialog_id\":" + dialogId + ",\"msg_id\":" + msgId + ",\"ts\":" + System.currentTimeMillis() + "}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }

            int codeResp = conn.getResponseCode();
            FileLog.d("TelegramFastLane: Relay to " + endpointUrl + " -> " + codeResp);
        } catch (Throwable t) {
            FileLog.e("TelegramFastLane: Direct HTTP relay error (" + endpointUrl + "): " + t.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
