package org.telegram.messenger.custom;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import java.util.*;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;

public class AutoReactionController {
    private static final Map<Integer, AutoReactionController> instances = new HashMap<>();
    private final int currentAccount;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isOneSecSwitchOn = false;
    private long activeChannelId = 0;
    private int latestMsgId = 0;
    private boolean inFloodWait = false;
    private WifiManager.WifiLock wifiLock = null;
    private final Random random = new Random();

    public static synchronized AutoReactionController getInstance(int account) {
        AutoReactionController instance = instances.get(account);
        if (instance == null) {
            instance = new AutoReactionController(account);
            instances.put(account, instance);
        }
        return instance;
    }

    private AutoReactionController(int account) {
        this.currentAccount = account;
    }

    public synchronized void setOneSecSwitch(boolean enabled, long channelId) {
        this.isOneSecSwitchOn = enabled;
        this.activeChannelId = channelId;

        // Manage OnePlus 15R FastConnect Wi-Fi 7 Low-Latency Lock
        Context context = ApplicationLoader.applicationContext;
        if (context != null) {
            try {
                if (enabled) {
                    if (wifiLock == null) {
                        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                        if (wm != null) {
                            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "tg:fastlane");
                            wifiLock.acquire();
                        }
                    }
                } else {
                    if (wifiLock != null && wifiLock.isHeld()) {
                        wifiLock.release();
                        wifiLock = null;
                    }
                }
            } catch (Exception ignored) {}
        }

        if (enabled) {
            startOneSecLoop();
        } else {
            handler.removeCallbacks(oneSecRunnable);
        }
    }

    private final Runnable oneSecRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isOneSecSwitchOn || inFloodWait || activeChannelId == 0) return;

            if (latestMsgId > 0) {
                // Dispatch dynamic allowed reaction
                dispatchRandomAllowedReaction(activeChannelId, latestMsgId);
            }

            // Also keep TCP cwnd pinned via non-mutating readHistory
            sendNonMutatingKeepAlive(activeChannelId, latestMsgId);

            handler.postDelayed(this, 1000); // 1.0s tick
        }
    };

    private void startOneSecLoop() {
        handler.removeCallbacks(oneSecRunnable);
        handler.post(oneSecRunnable);
    }

    public void updateLatestMessage(long channelId, int msgId) {
        if (channelId == this.activeChannelId) {
            this.latestMsgId = msgId;
        }
    }

    public void dispatchRandomAllowedReaction(long channelId, int msgId) {
        if (inFloodWait || channelId == 0 || msgId == 0) return;
        if (!GiftCodeInterceptor.isChannelTargeted(channelId)) return;

        TLRPC.ChatFull chatFull = MessagesController.getInstance(currentAccount).getChatFull(-channelId);
        List<String> allowedEmojis = getPermittedEmojis(chatFull);
        if (allowedEmojis.isEmpty()) return;

        String selectedEmoji = allowedEmojis.get(random.nextInt(allowedEmojis.size()));

        TLRPC.TL_messages_sendReaction req = new TLRPC.TL_messages_sendReaction();
        // CRITICAL FIX: Negative dialog ID (-channelId) is mandatory for channels/megagroups!
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(-channelId);
        req.msg_id = msgId;
        req.reaction = new ArrayList<>();
        TLRPC.TL_reactionEmoji reaction = new TLRPC.TL_reactionEmoji();
        reaction.emoticon = selectedEmoji;
        req.reaction.add(reaction);

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            if (error != null) {
                if (error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
                    handleFloodWait(error.text);
                }
            }
        });
    }

    private void sendNonMutatingKeepAlive(long channelId, int msgId) {
        try {
            TLRPC.TL_channels_readHistory req = new TLRPC.TL_channels_readHistory();
            req.channel = MessagesController.getInstance(currentAccount).getInputChannel(-channelId);
            req.max_id = msgId > 0 ? msgId : 1;
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, null);
        } catch (Exception ignored) {}
    }

    private List<String> getPermittedEmojis(TLRPC.ChatFull chatFull) {
        List<String> list = new ArrayList<>();
        if (chatFull == null || chatFull.available_reactions == null) {
            list.addAll(Arrays.asList("👍", "🔥", "❤️", "👏", "⚡"));
            return list;
        }
        if (chatFull.available_reactions instanceof TLRPC.TL_chatReactionsAll) {
            list.addAll(Arrays.asList("👍", "🔥", "❤️", "👏", "⚡", "🎉", "😎"));
        } else if (chatFull.available_reactions instanceof TLRPC.TL_chatReactionsSome) {
            TLRPC.TL_chatReactionsSome some = (TLRPC.TL_chatReactionsSome) chatFull.available_reactions;
            for (TLRPC.Reaction r : some.reactions) {
                if (r instanceof TLRPC.TL_reactionEmoji) {
                    list.add(((TLRPC.TL_reactionEmoji) r).emoticon);
                }
            }
        }
        if (list.isEmpty()) list.add("👍");
        return list;
    }

    private void handleFloodWait(String errorText) {
        try {
            int seconds = Integer.parseInt(errorText.replace("FLOOD_WAIT_", "").trim());
            inFloodWait = true;
            handler.postDelayed(() -> {
                inFloodWait = false;
                if (isOneSecSwitchOn) startOneSecLoop();
            }, (seconds + 1) * 1000L);
        } catch (Exception ignored) {}
    }
}
