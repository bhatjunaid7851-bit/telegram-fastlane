package org.telegram.messenger.custom;

import android.content.Context;
import android.content.Intent;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;

public class GiftCodeInterceptor {
    // Priority 1: Exact 32-character code
    private static final Pattern EXACT_32_PATTERN = Pattern.compile("\\b[A-Za-z0-9]{32}\\b");
    // Priority 2: Fallback 16-31 character code
    private static final Pattern FALLBACK_PATTERN = Pattern.compile("\\b[A-Za-z0-9]{16,31}\\b");

    private static final ExecutorService RELAY_EXECUTOR = Executors.newFixedThreadPool(2);
    private static String lastDispatchedCode = "";
    private static String legacy1RelayUrl = "https://srv1598644.hstgr.cloud:3001/tg/codes";
    private static boolean localDispatchEnabled = true;
    private static boolean legacy1RelayEnabled = true;

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

        // 1. Scan plain message text
        if (msg.message != null && msg.message.length() >= 16) {
            scanAndDispatch(currentAccount, channelId, msg.id, msg.message);
        }

        // 2. Scan hidden URL entities (in case code is embedded in a link)
        if (msg.entities != null) {
            for (int i = 0, size = msg.entities.size(); i < size; i++) {
                TLRPC.MessageEntity entity = msg.entities.get(i);
                if (entity instanceof TLRPC.TL_messageEntityTextUrl && entity.url != null) {
                    scanAndDispatch(currentAccount, channelId, msg.id, entity.url);
                }
            }
        }

        // 3. Scan reply markup inline keyboard buttons if present
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
        // First pass: look specifically for exact 32-char code
        Matcher m32 = EXACT_32_PATTERN.matcher(text);
        while (m32.find()) {
            String candidate = m32.group();
            if (isLikelyCode(candidate)) {
                triggerCapture(currentAccount, channelId, msgId, candidate);
                return;
            }
        }

        // Second pass: fallback 16-31 char code
        Matcher mFallback = FALLBACK_PATTERN.matcher(text);
        while (mFallback.find()) {
            String candidate = mFallback.group();
            if (isLikelyCode(candidate)) {
                triggerCapture(currentAccount, channelId, msgId, candidate);
                return;
            }
        }
    }

    private static boolean isLikelyCode(String s) {
        // Skip common URL paths or extensions
        if (s.equalsIgnoreCase("telegram") || s.equalsIgnoreCase("autoclaimer")) return false;
        
        boolean hasDigit = false;
        boolean hasLetter = false;
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') hasDigit = true;
            else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) hasLetter = true;
            if (hasDigit && hasLetter) return true;
        }
        return s.length() == 32; // Allow pure hex/alpha if exact 32 length
    }

    private static synchronized void triggerCapture(int currentAccount, long channelId, int msgId, String code) {
        if (code.equals(lastDispatchedCode)) return;
        lastDispatchedCode = code;
        long ts = System.currentTimeMillis();

        if (localDispatchEnabled) {
            dispatchToLocalClaimer(code, channelId, ts);
        }
        if (legacy1RelayEnabled && legacy1RelayUrl != null) {
            relayToLegacy1(code, channelId, ts);
        }
        AutoReactionController.getInstance(currentAccount).updateLatestMessage(channelId, msgId);
        // Instant drop reaction on successful capture (<5ms)
        AutoReactionController.getInstance(currentAccount).dispatchRandomAllowedReaction(channelId, msgId);
    }

    private static void dispatchToLocalClaimer(String code, long channelId, long ts) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) return;

        Intent intent = new Intent("com.tg.tgnative.ACTION_LOCAL_CODE");
        intent.setPackage("com.rajuofficial.gctgedition");
        // CRITICAL: Bypass background broadcast queue throttling on Android 16 / OxygenOS 16
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        intent.putExtra("code", code);
        intent.putExtra("channel_id", channelId);
        intent.putExtra("timestamp", ts);
        context.sendBroadcast(intent);
    }

    private static void relayToLegacy1(String code, long channelId, long ts) {
        RELAY_EXECUTOR.execute(() -> {
            try {
                URL url = new URL(legacy1RelayUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Connection", "Keep-Alive");
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(1500);
                conn.setDoOutput(true);

                String payload = String.format(
                    "{\"code\":\"%s\",\"channelId\":\"%d\",\"ts\":%d,\"source\":\"telegram_client\"}",
                    code, channelId, ts
                );
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                conn.getResponseCode();
            } catch (Exception ignored) {}
        });
    }
}
