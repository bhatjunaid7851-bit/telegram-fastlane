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
    private long activeDialogId = 0;
    private long activeChatId = 0;
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

    public synchronized void setOneSecSwitch(boolean enabled, long dialogId, long chatId) {
        this.isOneSecSwitchOn = enabled;
        this.activeDialogId = dialogId;
        this.activeChatId = chatId;

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
            if (!isOneSecSwitchOn || inFloodWait || activeDialogId == 0) return;

            if (latestMsgId > 0) {
                dispatchRandomAllowedReaction(activeDialogId, activeChatId, latestMsgId);
            }

            // Keep TCP cwnd pinned via non-mutating readHistory
            sendNonMutatingKeepAlive(activeDialogId, latestMsgId);

            handler.postDelayed(this, 1000);
        }
    };

    private void startOneSecLoop() {
        handler.removeCallbacks(oneSecRunnable);
        handler.post(oneSecRunnable);
    }

    public void updateLatestMessage(long dialogId, int msgId) {
        if (dialogId == this.activeDialogId) {
            this.latestMsgId = msgId;
        }
    }

    public void dispatchRandomAllowedReaction(long dialogId, long targetChatId, int msgId) {
        if (inFloodWait || dialogId == 0 || msgId == 0) return;
        
        long absId = Math.abs(targetChatId != 0 ? targetChatId : dialogId);
        if (!GiftCodeInterceptor.isChannelTargeted(absId) && !GiftCodeInterceptor.isChannelTargeted(targetChatId) && !GiftCodeInterceptor.isChannelTargeted(dialogId)) {
            return;
        }

        TLRPC.ChatFull chatFull = MessagesController.getInstance(currentAccount).getChatFull(absId);
        List<String> allowedEmojis = getPermittedEmojis(chatFull);
        if (allowedEmojis.isEmpty()) return;

        String selectedEmoji = allowedEmojis.get(random.nextInt(allowedEmojis.size()));

        TLRPC.TL_messages_sendReaction req = new TLRPC.TL_messages_sendReaction();
        TLRPC.InputPeer inputPeer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        if (inputPeer == null || (inputPeer instanceof TLRPC.TL_inputPeerChannel && ((TLRPC.TL_inputPeerChannel) inputPeer).access_hash == 0)) {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(absId);
            if (chat != null) {
                if (ChatObject.isChannel(chat)) {
                    TLRPC.TL_inputPeerChannel pc = new TLRPC.TL_inputPeerChannel();
                    pc.channel_id = absId;
                    pc.access_hash = chat.access_hash;
                    inputPeer = pc;
                } else {
                    TLRPC.TL_inputPeerChat pc = new TLRPC.TL_inputPeerChat();
                    pc.chat_id = absId;
                    inputPeer = pc;
                }
            }
        }
        if (inputPeer == null) return;

        req.peer = inputPeer;
        req.msg_id = msgId;
        req.reaction = new ArrayList<>();
        TLRPC.TL_reactionEmoji reaction = new TLRPC.TL_reactionEmoji();
        reaction.emoticon = selectedEmoji;
        req.reaction.add(reaction);
        req.flags |= 1; // CRITICAL MTProto bitmask: serializes reaction vector!

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.Updates) {
                AndroidUtilities.runOnUIThread(() -> {
                    MessagesController.getInstance(currentAccount).processUpdates((TLRPC.Updates) response, false);
                });
            }
            if (error != null) {
                FileLog.e("FastLane sendReaction error: " + error.code + " " + error.text);
                if (error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
                    handleFloodWait(error.text);
                }
            }
        });
    }

    private void sendNonMutatingKeepAlive(long dialogId, int msgId) {
        try {
            if (dialogId < 0) {
                long absId = -dialogId;
                TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(absId);
                if (ChatObject.isChannel(chat)) {
                    TLRPC.TL_channels_readHistory req = new TLRPC.TL_channels_readHistory();
                    req.channel = MessagesController.getInstance(currentAccount).getInputChannel(absId);
                    req.max_id = msgId > 0 ? msgId : 1;
                    ConnectionsManager.getInstance(currentAccount).sendRequest(req, null);
                }
            }
        } catch (Exception ignored) {}
    }

    private List<String> getPermittedEmojis(TLRPC.ChatFull chatFull) {
        List<String> list = new ArrayList<>();
        if (chatFull == null || chatFull.available_reactions == null) {
            list.addAll(Arrays.asList("👍", "🔥", "❤️", "👏", "⚡", "🎉", "😎"));
            return list;
        }
        if (chatFull.available_reactions instanceof TLRPC.TL_chatReactionsNone) {
            return Collections.emptyList();
        }
        if (chatFull.available_reactions instanceof TLRPC.TL_chatReactionsAll) {
            list.addAll(Arrays.asList("👍", "🔥", "❤️", "👏", "⚡", "🎉", "😎"));
            return list;
        }
        if (chatFull.available_reactions instanceof TLRPC.TL_chatReactionsSome) {
            TLRPC.TL_chatReactionsSome some = (TLRPC.TL_chatReactionsSome) chatFull.available_reactions;
            if (some.reactions != null) {
                for (TLRPC.Reaction r : some.reactions) {
                    if (r instanceof TLRPC.TL_reactionEmoji) {
                        list.add(((TLRPC.TL_reactionEmoji) r).emoticon);
                    }
                }
            }
        }
        if (list.isEmpty()) {
            list.addAll(Arrays.asList("👍", "🔥", "❤️", "👏", "⚡"));
        }
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
