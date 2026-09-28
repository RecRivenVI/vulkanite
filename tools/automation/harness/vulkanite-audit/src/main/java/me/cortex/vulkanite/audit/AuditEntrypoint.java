package me.cortex.vulkanite.audit;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Dev-only automation control. Listens on 127.0.0.1 only when enabled and a
 * token file is present. Never starts for a normal user launch.
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
        try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {
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
                case "setblock" -> write(out, setBlock(parts));
                case "time" -> write(out, setTime(parts));
                case "weather" -> write(out, setWeather(parts));
                case "teleport" -> write(out, teleport(parts));
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

    private static String snapshotJson() {
        // Reflection keeps harness compilable against vulkanite at runtime only.
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
                sb.append('"').append(c.getName()).append("\":")
                  .append(c.getAccessor().invoke(snap));
            }
            sb.append("}}");
            return sb.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + t.getClass().getSimpleName() + "\"}";
        }
    }

    private static String setBlock(String[] parts) {
        // setblock <token> <x> <y> <z> <block>
        if (parts.length < 6) return "{\"ok\":false,\"error\":\"setblock token x y z block\"}";
        int x = Integer.parseInt(parts[2]);
        int y = Integer.parseInt(parts[3]);
        int z = Integer.parseInt(parts[4]);
        String blockId = parts[5];
        boolean[] ok = {false};
        Minecraft.getInstance().execute(() -> {
            Level level = Minecraft.getInstance().level;
            if (level == null) return;
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                    net.minecraft.resources.ResourceLocation.parse(blockId));
            ok[0] = level.setBlock(new BlockPos(x, y, z),
                    block.defaultBlockState(), 3);
        });
        return "{\"ok\":" + ok[0] + ",\"cmd\":\"setblock\"}";
    }

    private static String setTime(String[] parts) {
        if (parts.length < 3) return "{\"ok\":false,\"error\":\"time token value\"}";
        long value = Long.parseLong(parts[2]);
        Minecraft.getInstance().execute(() -> {
            Level level = Minecraft.getInstance().level;
            if (level != null) level.setDayTime(value);
        });
        return "{\"ok\":true,\"cmd\":\"time\"}";
    }

    private static String setWeather(String[] parts) {
        if (parts.length < 3) return "{\"ok\":false,\"error\":\"weather token clear|rain\"}";
        boolean rain = "rain".equalsIgnoreCase(parts[2]);
        Minecraft.getInstance().execute(() -> {
            Level level = Minecraft.getInstance().level;
            if (level == null) return;
            var server = level.getServer();
            if (server == null) return;
            for (var l : server.getAllLevels()) {
                l.setRainLevel(rain ? 1f : 0f);
                l.setThunderLevel(rain ? 1f : 0f);
            }
        });
        return "{\"ok\":true,\"cmd\":\"weather\"}";
    }

    private static String teleport(String[] parts) {
        if (parts.length < 6) return "{\"ok\":false,\"error\":\"teleport token x y z\"}";
        double x = Double.parseDouble(parts[2]);
        double y = Double.parseDouble(parts[3]);
        double z = Double.parseDouble(parts[4]);
        float yaw = parts.length > 6 ? Float.parseFloat(parts[5]) : 0f;
        float pitch = parts.length > 7 ? Float.parseFloat(parts[6]) : 0f;
        Minecraft.getInstance().execute(() -> {
            var player = Minecraft.getInstance().player;
            if (player != null) player.moveTo(x, y, z, yaw, pitch);
        });
        return "{\"ok\":true,\"cmd\":\"teleport\"}";
    }
}
