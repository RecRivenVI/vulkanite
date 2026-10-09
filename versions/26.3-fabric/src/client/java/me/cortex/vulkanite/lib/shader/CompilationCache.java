package me.cortex.vulkanite.lib.shader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** Integrity-checked derived data. Cache I/O must never prevent loading a pack. */
public final class CompilationCache {
    private static final int MAGIC = 0x564b4331, HEADER = 72;
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("Vulkanite/Compilation");

    private CompilationCache() {}

    public static Path root() {
        return Path.of(".vulkanite-cache").toAbsolutePath();
    }

    public static String key(String... inputs) {
        var digest = digest();
        for (String input : inputs) {
            byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String hash(byte[] data) {
        return HexFormat.of().formatHex(digest().digest(data));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static Path file(String area, String key) {
        if (!area.matches("[a-z0-9-]+") || !key.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid compilation cache key");
        return root().resolve(area).resolve(key + ".bin");
    }

    public static byte[] read(String area, String key, int maxBytes) {
        try {
            Path file = file(area, key);
            long size = Files.size(file);
            if (size < HEADER || size > (long) maxBytes + HEADER) return null;
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length < HEADER || bytes.length > (long) maxBytes + HEADER) return null;
            var header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            if (header.getInt() != MAGIC || header.getInt() != bytes.length - HEADER) return null;
            byte[] storedKey = new byte[32];
            header.get(storedKey);
            if (!Arrays.equals(storedKey, HexFormat.of().parseHex(key))) return null;
            byte[] checksum = new byte[32];
            header.get(checksum);
            byte[] data = Arrays.copyOfRange(bytes, HEADER, bytes.length);
            if (!MessageDigest.isEqual(checksum, digest().digest(data))) {
                LOG.warn("Ignoring corrupt {} cache {}", area, key.substring(0, 12));
                return null;
            }
            return data;
        } catch (NoSuchFileException missing) {
            return null;
        } catch (IOException | SecurityException | InvalidPathException error) {
            LOG.warn("Cannot read {} cache: {}", area, error.toString());
            return null;
        }
    }

    public static void write(String area, String key, byte[] data) {
        Path temporary = null;
        try {
            Path file = file(area, key);
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), key.substring(0, 12), ".tmp");
            var bytes = ByteBuffer.allocate(HEADER + data.length).order(ByteOrder.BIG_ENDIAN);
            bytes.putInt(MAGIC)
                    .putInt(data.length)
                    .put(HexFormat.of().parseHex(key))
                    .put(digest().digest(data))
                    .put(data);
            Files.write(temporary, bytes.array());
            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | SecurityException | InvalidPathException error) {
            LOG.warn("Cannot write {} cache: {}", area, error.toString());
        } finally {
            if (temporary != null)
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | SecurityException ignored) {
                }
        }
    }
}
