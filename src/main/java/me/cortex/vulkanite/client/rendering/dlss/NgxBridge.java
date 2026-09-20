package me.cortex.vulkanite.client.rendering.dlss;

import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.LoggerFactory;

/** Optional native capability; no shader pack is permitted to supply executable code. */
public final class NgxBridge {
    public static final Path DIRECTORY = Path.of(System.getProperty("vulkanite.dlss.directory", "config/vulkanite/dlss")).toAbsolutePath().normalize();
    private static boolean attempted, loaded;
    private static String unavailable;
    public static synchronized boolean available() {
        if (!attempted) {
            attempted = true;
            try {
                if (Boolean.getBoolean("vulkanite.dlss.disabled")) throw new IllegalStateException("disabled by user");
                var library = DIRECTORY.resolve(System.mapLibraryName("vulkanite_dlss"));
                if (!Files.isRegularFile(library)) throw new IllegalStateException("bridge not installed at " + library);
                System.load(library.toString());
                if(bridgeVersion()!=2) throw new IllegalStateException("Native bridge version mismatch; install the current bridge");
                loaded = true;
            } catch (LinkageError | RuntimeException error) { disable(error.toString()); }
        }
        return loaded && unavailable == null;
    }
    public static void disable(String reason) {
        unavailable = reason;
        LoggerFactory.getLogger("Vulkanite/DLSS").warn("DLSS unavailable; pack fallback retained: {}", reason);
    }
    public static native String[] requirements(long instance, long physicalDevice, String directory);
    public static native int bridgeVersion();
    public static native long open(long instance, long physicalDevice, long device, String directory, int mode, int quality, int width, int height);
    public static native int[] dimensions(long session);
    public static native void create(long session, long command);
    public static native void evaluate(long session, long command, long[] resources, float[] constants);
    public static native void close(long session);
}
