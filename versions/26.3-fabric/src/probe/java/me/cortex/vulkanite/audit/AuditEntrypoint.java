package me.cortex.vulkanite.audit;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev-only automation control. Listens on 127.0.0.1 only when enabled and a token is present. Inert
 * for normal launches. World mutation uses reflection so this class does not pin every MC mapping
 * name at compile time.
 */
public final class AuditEntrypoint implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("VulkaniteAudit");

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("vulkanite.audit.enable")) {
            LOG.info("vulkanite-audit disabled (no -Dvulkanite.audit.enable=true)");
            return;
        }
        String token = System.getProperty("vulkanite.audit.token", "");
        if (token.isEmpty()) {
            LOG.error("vulkanite-audit enable without token; refusing to listen");
            return;
        }
        int port = Integer.getInteger("vulkanite.audit.port", 53117);
        Thread t = new Thread(() -> serve(port, token), "vulkanite-audit-ipc");
        t.setDaemon(true);
        t.start();
        LOG.info("vulkanite-audit listening on 127.0.0.1:{}", port);
    }

    private static void serve(int port, String token) {
        try (ServerSocket server = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            while (true) {
                try (Socket client = server.accept()) {
                    handle(client, token);
                } catch (Exception e) {
                    LOG.warn("audit client error", e);
                }
            }
        } catch (Exception e) {
            LOG.error("audit server failed", e);
        }
    }

    private static void handle(Socket client, String token) throws Exception {
        client.setSoTimeout(30_000);
        try (BufferedReader in =
                        new BufferedReader(
                                new InputStreamReader(
                                        client.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter out =
                        new BufferedWriter(
                                new OutputStreamWriter(
                                        client.getOutputStream(), StandardCharsets.UTF_8))) {
            String line = in.readLine();
            if (line == null) return;
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2 || !token.equals(parts[1])) {
                write(out, "{\"ok\":false,\"error\":\"unauthorized\"}");
                return;
            }
            String cmd = parts[0].toLowerCase(Locale.ROOT);
            switch (cmd) {
                case "ping" -> write(out, "{\"ok\":true,\"cmd\":\"ping\"}");
                case "snapshot" -> write(out, snapshotJson());
                case "frames" -> write(out, framesJson());
                case "resetperf" -> write(out, resetPerfJson());
                case "setblock" -> write(out, asyncCmd(() -> setBlock(parts)));
                case "time" -> write(out, asyncCmd(() -> setTime(parts)));
                case "weather" ->
                        write(
                                out,
                                "{\"ok\":true,\"cmd\":\"weather\",\"note\":\"use setblock/time\"}");
                case "teleport" -> write(out, asyncCmd(() -> teleport(parts)));
                case "quit" -> {
                    write(out, "{\"ok\":true,\"cmd\":\"quit\"}");
                    Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
                }
                default -> write(out, "{\"ok\":false,\"error\":\"unknown cmd\"}");
            }
        }
    }

    private static void write(BufferedWriter out, String json) throws Exception {
        out.write(json);
        out.write('\n');
        out.flush();
    }

    private static String asyncCmd(java.util.concurrent.Callable<String> body) {
        try {
            var future = new java.util.concurrent.CompletableFuture<String>();
            Minecraft.getInstance()
                    .execute(
                            () -> {
                                try {
                                    future.complete(body.call());
                                } catch (Throwable t) {
                                    future.complete(
                                            "{\"ok\":false,\"error\":\""
                                                    + t.getClass().getSimpleName()
                                                    + "\"}");
                                }
                            });
            return future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    private static String resetPerfJson() {
        try {
            Class<?> cls = Class.forName("me.cortex.vulkanite.audit.Diagnostics");
            cls.getMethod("resetPerf").invoke(null);
            return "{\"ok\":true,\"cmd\":\"resetperf\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + t.getClass().getSimpleName() + "\"}";
        }
    }

    private static String framesJson() {
        try {
            Class<?> cls = Class.forName("me.cortex.vulkanite.audit.Diagnostics");
            long[] frames = (long[]) cls.getMethod("frameTimesCopy").invoke(null);
            var sb = new StringBuilder("{\"ok\":true,\"cmd\":\"frames\",\"data\":[");
            for (int i = 0; i < frames.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(frames[i]);
            }
            sb.append("]}");
            return sb.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + t.getClass().getSimpleName() + "\"}";
        }
    }

    private static String snapshotJson() {
        try {
            Class<?> cls = Class.forName("me.cortex.vulkanite.audit.Diagnostics");
            Object snap = cls.getMethod("snapshot").invoke(null);
            var rec = (java.lang.Record) snap;
            var sb = new StringBuilder();
            sb.append("{\"ok\":true,\"cmd\":\"snapshot\",\"data\":{");
            boolean first = true;
            for (var c : rec.getClass().getRecordComponents()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"')
                        .append(c.getName())
                        .append("\":")
                        .append(c.getAccessor().invoke(snap));
            }
            sb.append("}}");
            return sb.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + t.getClass().getSimpleName() + "\"}";
        }
    }

    private static Object level() {
        Minecraft mc = Minecraft.getInstance();
        for (String name : new String[] {"level", "getLevel"}) {
            try {
                var f = mc.getClass().getField(name);
                Object v = f.get(mc);
                if (v != null) return v;
            } catch (Exception ignored) {
            }
            try {
                Method m = mc.getClass().getMethod(name);
                Object v = m.invoke(mc);
                if (v != null) return v;
            } catch (Exception ignored) {
            }
        }
        try {
            var f = mc.getClass().getDeclaredField("level");
            f.setAccessible(true);
            return f.get(mc);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Object player() {
        Minecraft mc = Minecraft.getInstance();
        for (String name : new String[] {"player", "getPlayer"}) {
            try {
                var f = mc.getClass().getField(name);
                Object v = f.get(mc);
                if (v != null) return v;
            } catch (Exception ignored) {
            }
            try {
                Method m = mc.getClass().getMethod(name);
                Object v = m.invoke(mc);
                if (v != null) return v;
            } catch (Exception ignored) {
            }
        }
        try {
            var f = mc.getClass().getDeclaredField("player");
            f.setAccessible(true);
            return f.get(mc);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String setBlock(String[] parts) {
        if (parts.length < 6) return "{\"ok\":false,\"error\":\"setblock token x y z block\"}";
        int x = Integer.parseInt(parts[2]);
        int y = Integer.parseInt(parts[3]);
        int z = Integer.parseInt(parts[4]);
        String blockId = parts[5];
        try {
            var server = Minecraft.getInstance().getSingleplayerServer();
            if (server == null) return "{\"ok\":false,\"error\":\"no server\"}";
            String cmd = "setblock " + x + " " + y + " " + z + " " + blockId;
            var source = server.createCommandSourceStack().withSuppressedOutput();
            server.getCommands().performPrefixedCommand(source, cmd);
            return "{\"ok\":true,\"cmd\":\"setblock\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"setblock " + t.getClass().getSimpleName() + "\"}";
        }
    }

    private static String setTime(String[] parts) {
        if (parts.length < 3) return "{\"ok\":false,\"error\":\"time token value\"}";
        String value = parts[2];
        try {
            var server = Minecraft.getInstance().getSingleplayerServer();
            if (server == null) return "{\"ok\":false,\"error\":\"no server\"}";
            var source = server.createCommandSourceStack().withSuppressedOutput();
            server.getCommands().performPrefixedCommand(source, "time set " + value);
            return "{\"ok\":true,\"cmd\":\"time\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"time " + t.getClass().getSimpleName() + "\"}";
        }
    }

    private static String teleport(String[] parts) {
        if (parts.length < 5) return "{\"ok\":false,\"error\":\"teleport token x y z\"}";
        double x = Double.parseDouble(parts[2]);
        double y = Double.parseDouble(parts[3]);
        double z = Double.parseDouble(parts[4]);
        Object p = player();
        if (p == null) return "{\"ok\":false,\"error\":\"no player\"}";
        try {
            for (String name : new String[] {"setPos", "moveTo", "teleportTo", "absMoveTo"}) {
                for (Method m : p.getClass().getMethods()) {
                    if (!m.getName().equals(name) || m.getParameterCount() != 3) continue;
                    Class<?>[] t = m.getParameterTypes();
                    if (t[0] == double.class || t[0] == float.class) {
                        m.invoke(p, x, y, z);
                        return "{\"ok\":true,\"cmd\":\"teleport\",\"method\":\"" + name + "\"}";
                    }
                }
            }
            return "{\"ok\":false,\"error\":\"no teleport\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"teleport " + t.getClass().getSimpleName() + "\"}";
        }
    }
}
