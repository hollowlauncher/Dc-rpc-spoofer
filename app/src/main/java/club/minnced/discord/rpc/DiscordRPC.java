package club.minnced.discord.rpc;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drop-in replacement for the JNA-backed {@code club.minnced.discord.rpc.DiscordRPC}
 * interface shipped in java-discord-rpc-v2.0.2.jar / discord-rpc-release-v3.4.0.jar.
 *
 * WHY THIS EXISTS
 * ----------------
 * The original interface loads a native library via
 * {@code com.sun.jna.Native.loadLibrary("discord-rpc", DiscordRPC.class)}.
 * discord-rpc-release-v3.4.0.jar only bundles:
 *   darwin/libdiscord-rpc.dylib
 *   linux-x86-64/libdiscord-rpc.so
 *   win32-x86(-64)/discord-rpc.dll
 * There is no android-aarch64 (or any Android) build, so on device JNA's
 * static initializer throws UnsatisfiedLinkError inside DiscordRPC.<clinit>,
 * which crashes the whole game process instead of just failing the RPC
 * feature.
 *
 * There is no NDK/cross-compiler available to build a real arm64 .so for
 * you here, and honestly you don't need one: Discord's local Rich Presence
 * protocol is just newline-free length-prefixed JSON over a local Unix
 * domain socket (discord-ipc-0..9). That's implementable in plain Java /
 * Android APIs with zero native code, which is what this class does.
 *
 * DROP-IN COMPATIBILITY
 * ----------------------
 * Same package, same class/interface name, same INSTANCE field, same
 * method signatures as the original. Anything already compiled against
 * club.minnced.discord.rpc.DiscordRPC (e.g. a mod that does
 * DiscordRPC.INSTANCE.Discord_Initialize(...)) will bind to this class
 * instead, as long as this class wins classpath/dex ordering over the one
 * inside java-discord-rpc-v2.0.2.jar (e.g. put your module's classes/dex
 * before that dependency, or exclude the original artifact and keep only
 * the *_release native-asset jar for the DiscordEventHandlers /
 * DiscordRichPresence / DiscordUser structure classes, which this class
 * reuses unmodified via reflection).
 *
 * IMPORTANT CAVEAT - READ THIS
 * -----------------------------
 * Rich Presence IPC only works if something is actually listening on the
 * discord-ipc-N socket on the SAME device. That listener is normally the
 * Discord desktop app. Stock Android has no such listener - the Discord
 * Android app does not expose this socket - so on a plain phone
 * Discord_Initialize will simply never see a READY event. That is the
 * correct, non-crashing behaviour (identical to running the desktop
 * library with Discord closed): Discord_RunCallbacks() will keep retrying
 * quietly in the background instead of killing your app. If you pair this
 * with something that *does* expose an abstract unix socket named
 * discord-ipc-0..9 (e.g. a bridge/proxy you run yourself), it will connect
 * to that automatically - no code changes needed.
 */
public interface DiscordRPC {

    DiscordRPC INSTANCE = new Impl();

    int DISCORD_REPLY_NO = 0;
    int DISCORD_REPLY_YES = 1;
    int DISCORD_REPLY_IGNORE = 2;

    void Discord_Initialize(String applicationId, DiscordEventHandlers handlers, boolean autoRegister, String optionalSteamId);
    void Discord_Shutdown();
    void Discord_RunCallbacks();
    void Discord_UpdateConnection();
    void Discord_UpdatePresence(DiscordRichPresence presence);
    void Discord_ClearPresence();
    void Discord_Respond(String userId, int reply);
    void Discord_UpdateHandlers(DiscordEventHandlers handlers);
    void Discord_Register(String applicationId, String command);
    void Discord_RegisterSteamGame(String applicationId, String steamId);

    /* ==================================================================== */

    final class Impl implements DiscordRPC {
        private static final String TAG = "DiscordRPC-Android";
        private static final int OP_HANDSHAKE = 0;
        private static final int OP_FRAME = 1;
        private static final int OP_CLOSE = 2;
        private static final int OP_PING = 3;
        private static final int OP_PONG = 4;

        private volatile String applicationId;
        private volatile DiscordEventHandlers handlers;
        private volatile LocalSocket socket;
        private volatile OutputStream out;
        private volatile InputStream in;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean connected = new AtomicBoolean(false);
        private Thread ioThread;

        private final BlockingQueue<Runnable> callbackQueue = new ArrayBlockingQueue<>(64);

        @Override
        public void Discord_Initialize(String applicationId, DiscordEventHandlers handlers, boolean autoRegister, String optionalSteamId) {
            try {
                this.applicationId = applicationId;
                this.handlers = handlers;
                if (running.compareAndSet(false, true)) {
                    ioThread = new Thread(this::ioLoop, "DiscordIPC-Android");
                    ioThread.setDaemon(true);
                    ioThread.start();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Discord_Initialize failed (non-fatal)", t);
            }
        }

        @Override
        public void Discord_Shutdown() {
            running.set(false);
            closeQuietly();
            if (ioThread != null) ioThread.interrupt();
        }

        @Override
        public void Discord_RunCallbacks() {
            // Drain and execute callbacks on the caller's thread (matches
            // upstream semantics: RunCallbacks() is where onReady/onDisconnected
            // etc. actually fire, so it's safe to call every game tick).
            Runnable r;
            while ((r = callbackQueue.poll()) != null) {
                try {
                    r.run();
                } catch (Throwable t) {
                    Log.w(TAG, "RPC callback threw", t);
                }
            }
        }

        @Override
        public void Discord_UpdateConnection() { /* no-op: single persistent connection managed by ioLoop */ }

        @Override
        public void Discord_UpdatePresence(DiscordRichPresence presence) {
            if (!connected.get()) return;
            try {
                String json = buildSetActivityFrame(presence);
                sendFrame(OP_FRAME, json);
            } catch (Throwable t) {
                Log.w(TAG, "Discord_UpdatePresence failed (non-fatal)", t);
            }
        }

        @Override
        public void Discord_ClearPresence() {
            if (!connected.get()) return;
            try {
                sendFrame(OP_FRAME, "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":" + android.os.Process.myPid() + ",\"activity\":null},\"nonce\":\"" + UUID.randomUUID() + "\"}");
            } catch (Throwable t) {
                Log.w(TAG, "Discord_ClearPresence failed (non-fatal)", t);
            }
        }

        @Override
        public void Discord_Respond(String userId, int reply) {
            if (!connected.get()) return;
            try {
                sendFrame(OP_FRAME, "{\"cmd\":\"SEND_ACTIVITY_JOIN_INVITE\",\"args\":{\"user_id\":\"" + esc(userId) + "\"},\"nonce\":\"" + UUID.randomUUID() + "\"}");
            } catch (Throwable t) {
                Log.w(TAG, "Discord_Respond failed (non-fatal)", t);
            }
        }

        @Override
        public void Discord_UpdateHandlers(DiscordEventHandlers handlers) {
            this.handlers = handlers;
        }

        @Override
        public void Discord_Register(String applicationId, String command) { /* no desktop registry on Android */ }

        @Override
        public void Discord_RegisterSteamGame(String applicationId, String steamId) { /* no desktop registry on Android */ }

        /* ---------------------------- internals ---------------------------- */

        private void ioLoop() {
            while (running.get()) {
                try {
                    if (!connect()) {
                        sleep(5000);
                        continue;
                    }
                    handshake();
                    readLoop();
                } catch (Throwable t) {
                    Log.d(TAG, "IPC loop: " + t);
                } finally {
                    connected.set(false);
                    closeQuietly();
                }
                if (running.get()) sleep(5000); // retry, mirrors desktop lib's reconnect behaviour
            }
        }

        private boolean connect() {
            for (int i = 0; i < 10; i++) {
                try {
                    LocalSocket s = new LocalSocket();
                    // Discord uses the Linux abstract socket namespace for its IPC pipe.
                    s.connect(new LocalSocketAddress("discord-ipc-" + i, LocalSocketAddress.Namespace.ABSTRACT));
                    socket = s;
                    out = s.getOutputStream();
                    in = s.getInputStream();
                    return true;
                } catch (IOException ignored) {
                    // nothing listening on this slot; try the next one
                }
            }
            return false; // no local Discord IPC listener found on this device right now
        }

        private void handshake() throws IOException {
            String payload = "{\"v\":1,\"client_id\":\"" + esc(applicationId) + "\"}";
            sendFrame(OP_HANDSHAKE, payload);
        }

        private void readLoop() throws IOException {
            byte[] header = new byte[8];
            while (running.get()) {
                readFully(header);
                ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                int op = bb.getInt();
                int len = bb.getInt();
                byte[] payload = new byte[len];
                readFully(payload);
                String json = new String(payload, StandardCharsets.UTF_8);
                handleIncoming(op, json);
            }
        }

        private void handleIncoming(int op, String json) {
            if (op == OP_PING) {
                trySend(OP_PONG, json);
                return;
            }
            if (op == OP_CLOSE) {
                running.set(false);
                return;
            }
            if (op != OP_FRAME) return;

            String evt = extractString(json, "\"evt\"");
            final DiscordEventHandlers h = handlers;
            if (h == null) return;

            if ("READY".equals(evt)) {
                connected.set(true);
                if (h.ready != null) {
                    DiscordUser user = parseUser(json);
                    callbackQueue.offer(() -> invokeCallback(h.ready, user));
                }
            } else if ("ERROR".equals(evt)) {
                if (h.errored != null) {
                    int code = extractInt(json, "\"code\"");
                    String message = extractString(json, "\"message\"");
                    callbackQueue.offer(() -> invokeStatus(h.errored, code, message));
                }
            } else if ("ACTIVITY_JOIN".equals(evt)) {
                if (h.joinGame != null) {
                    String secret = extractString(json, "\"secret\"");
                    callbackQueue.offer(() -> invokeGameUpdate(h.joinGame, secret));
                }
            } else if ("ACTIVITY_SPECTATE".equals(evt)) {
                if (h.spectateGame != null) {
                    String secret = extractString(json, "\"secret\"");
                    callbackQueue.offer(() -> invokeGameUpdate(h.spectateGame, secret));
                }
            } else if ("ACTIVITY_JOIN_REQUEST".equals(evt)) {
                if (h.joinRequest != null) {
                    DiscordUser user = parseUser(json);
                    callbackQueue.offer(() -> invokeCallback(h.joinRequest, user));
                }
            }
        }

        // handlers.ready / joinRequest are OnReady / OnJoinRequest: void accept(DiscordUser)
        private void invokeCallback(Object callbackIface, DiscordUser user) {
            invokeSingleMethod(callbackIface, new Class<?>[]{DiscordUser.class}, new Object[]{user});
        }

        // handlers.disconnected / errored are OnStatus: void accept(int, String)
        private void invokeStatus(Object callbackIface, int code, String message) {
            invokeSingleMethod(callbackIface, new Class<?>[]{int.class, String.class}, new Object[]{code, message});
        }

        // handlers.joinGame / spectateGame are OnGameUpdate: void accept(String)
        private void invokeGameUpdate(Object callbackIface, String secret) {
            invokeSingleMethod(callbackIface, new Class<?>[]{String.class}, new Object[]{secret});
        }

        private void invokeSingleMethod(Object target, Class<?>[] paramTypes, Object[] args) {
            try {
                for (Method m : target.getClass().getMethods()) {
                    if (m.getName().equals("accept") && m.getParameterTypes().length == paramTypes.length) {
                        m.invoke(target, args);
                        return;
                    }
                }
                // functional interfaces implemented as lambdas often only appear via
                // getMethods() on the interface itself when target is a Proxy/lambda;
                // fall back to invoking through Proxy machinery if needed.
                if (Proxy.isProxyClass(target.getClass())) {
                    InvocationHandler ih = Proxy.getInvocationHandler(target);
                    ih.invoke(target, target.getClass().getMethod("accept", paramTypes), args);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to invoke RPC callback", t);
            }
        }

        private DiscordUser parseUser(String json) {
            try {
                DiscordUser user = new DiscordUser();
                setField(user, "userId", extractString(json, "\"id\""));
                setField(user, "username", extractString(json, "\"username\""));
                setField(user, "discriminator", extractString(json, "\"discriminator\""));
                setField(user, "avatar", extractString(json, "\"avatar\""));
                return user;
            } catch (Throwable t) {
                return null;
            }
        }

        private void setField(Object target, String name, Object value) {
            if (value == null) return;
            try {
                Field f = target.getClass().getField(name);
                f.set(target, value);
            } catch (Throwable ignored) {
                // field renamed/absent in this lib version - safe to skip
            }
        }

        private Object getField(Object target, String name) {
            try {
                Field f = target.getClass().getField(name);
                return f.get(target);
            } catch (Throwable t) {
                return null;
            }
        }

        private String buildSetActivityFrame(DiscordRichPresence p) {
            StringBuilder activity = new StringBuilder("{");
            appendStr(activity, "state", getField(p, "state"));
            appendStr(activity, "details", getField(p, "details"));

            Object start = getField(p, "startTimestamp");
            Object end = getField(p, "endTimestamp");
            if (isNonZero(start) || isNonZero(end)) {
                activity.append("\"timestamps\":{");
                boolean wroteStart = appendNum(activity, "start", start);
                if (isNonZero(end)) {
                    if (wroteStart) activity.append(',');
                    appendNum(activity, "end", end);
                }
                activity.append("},");
            }

            Object largeKey = getField(p, "largeImageKey");
            Object largeText = getField(p, "largeImageText");
            Object smallKey = getField(p, "smallImageKey");
            Object smallText = getField(p, "smallImageText");
            if (largeKey != null || largeText != null || smallKey != null || smallText != null) {
                activity.append("\"assets\":{");
                boolean any = false;
                any |= appendStrField(activity, "large_image", largeKey, any);
                any |= appendStrField(activity, "large_text", largeText, any);
                any |= appendStrField(activity, "small_image", smallKey, any);
                any |= appendStrField(activity, "small_text", smallText, any);
                activity.append("},");
            }

            Object partyId = getField(p, "partyId");
            if (partyId != null) {
                activity.append("\"party\":{");
                appendStr(activity, "id", partyId);
                Object partySize = getField(p, "partySize");
                Object partyMax = getField(p, "partyMax");
                if (isNonZero(partySize) || isNonZero(partyMax)) {
                    trimTrailingComma(activity);
                    activity.append(",\"size\":[")
                            .append(numOrZero(partySize)).append(',')
                            .append(numOrZero(partyMax)).append(']');
                }
                activity.append("},");
            }

            Object joinSecret = getField(p, "joinSecret");
            Object spectateSecret = getField(p, "spectateSecret");
            Object matchSecret = getField(p, "matchSecret");
            if (joinSecret != null || spectateSecret != null || matchSecret != null) {
                activity.append("\"secrets\":{");
                boolean any = false;
                any |= appendStrField(activity, "join", joinSecret, any);
                any |= appendStrField(activity, "spectate", spectateSecret, any);
                appendStrField(activity, "match", matchSecret, any);
                activity.append("},");
            }

            trimTrailingComma(activity);
            activity.append('}');

            return "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":" + android.os.Process.myPid()
                    + ",\"activity\":" + activity + "},\"nonce\":\"" + UUID.randomUUID() + "\"}";
        }

        private void appendStr(StringBuilder sb, String key, Object value) {
            if (value == null) return;
            sb.append('"').append(key).append("\":\"").append(esc(String.valueOf(value))).append("\",");
        }

        private boolean appendStrField(StringBuilder sb, String key, Object value, boolean prevWritten) {
            if (value == null) return false;
            if (prevWritten) sb.append(',');
            sb.append('"').append(key).append("\":\"").append(esc(String.valueOf(value))).append('"');
            return true;
        }

        private boolean appendNum(StringBuilder sb, String key, Object value) {
            if (!isNonZero(value)) return false;
            sb.append('"').append(key).append("\":").append(numOrZero(value));
            return true;
        }

        private boolean isNonZero(Object o) {
            if (o == null) return false;
            if (o instanceof Number) return ((Number) o).longValue() != 0L;
            return false;
        }

        private long numOrZero(Object o) {
            return (o instanceof Number) ? ((Number) o).longValue() : 0L;
        }

        private void trimTrailingComma(StringBuilder sb) {
            int len = sb.length();
            if (len > 0 && sb.charAt(len - 1) == ',') sb.setLength(len - 1);
        }

        private String esc(String s) {
            if (s == null) return "";
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') sb.append('\\');
                if (c == '\n') { sb.append("\\n"); continue; }
                sb.append(c);
            }
            return sb.toString();
        }

        // Minimal, dependency-free extraction of a top-level-ish string/int
        // value by key. Not a general JSON parser - deliberately small since
        // we only need a handful of known fields out of Discord's IPC frames.
        private String extractString(String json, String quotedKey) {
            int i = json.indexOf(quotedKey);
            if (i < 0) return null;
            int colon = json.indexOf(':', i + quotedKey.length());
            if (colon < 0) return null;
            int start = json.indexOf('"', colon + 1);
            if (start < 0) return null;
            int end = start + 1;
            StringBuilder sb = new StringBuilder();
            while (end < json.length() && json.charAt(end) != '"') {
                char c = json.charAt(end);
                if (c == '\\' && end + 1 < json.length()) {
                    end++;
                    c = json.charAt(end);
                }
                sb.append(c);
                end++;
            }
            return sb.toString();
        }

        private int extractInt(String json, String quotedKey) {
            int i = json.indexOf(quotedKey);
            if (i < 0) return 0;
            int colon = json.indexOf(':', i + quotedKey.length());
            if (colon < 0) return 0;
            int start = colon + 1;
            while (start < json.length() && (json.charAt(start) == ' ')) start++;
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
            try {
                return Integer.parseInt(json.substring(start, end));
            } catch (Exception e) {
                return 0;
            }
        }

        private synchronized void sendFrame(int op, String json) throws IOException {
            OutputStream o = out;
            if (o == null) return;
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(op).putInt(payload.length);
            o.write(bb.array());
            o.write(payload);
            o.flush();
        }

        private void trySend(int op, String json) {
            try { sendFrame(op, json); } catch (IOException ignored) { }
        }

        private void readFully(byte[] buf) throws IOException {
            int off = 0;
            InputStream i = in;
            if (i == null) throw new IOException("socket closed");
            while (off < buf.length) {
                int n = i.read(buf, off, buf.length - off);
                if (n < 0) throw new IOException("stream closed");
                off += n;
            }
        }

        private void closeQuietly() {
            try { if (out != null) out.close(); } catch (IOException ignored) { }
            try { if (in != null) in.close(); } catch (IOException ignored) { }
            try { if (socket != null) socket.close(); } catch (IOException ignored) { }
            out = null;
            in = null;
            socket = null;
        }

        private void sleep(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }
}
