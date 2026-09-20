package me.cortex.vulkanite.client.rendering.sharc;

import java.util.regex.Pattern;

/** Explicit shader-pack opt-in for the host-owned SHaRC cache. */
public record SharcRequest(int version, boolean enabled, int capacityPower) {
    public static final int MIN_CAPACITY_POWER = 16;
    public static final int MAX_CAPACITY_POWER = 24;

    public static SharcRequest parse(String source) {
        String clean = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        if (!clean.contains("vulkaniteSharcVersion")) return null;
        int version = literal(clean, "vulkaniteSharcVersion");
        if (version != 1) throw new IllegalArgumentException("Unsupported SHaRC ABI: " + version);
        int enabled = literal(clean, "vulkaniteSharcEnabled");
        int capacityPower = literal(clean, "vulkaniteSharcCapacityPower");
        if (enabled < 0 || enabled > 1)
            throw new IllegalArgumentException("vulkaniteSharcEnabled must be 0 or 1");
        if (capacityPower < MIN_CAPACITY_POWER || capacityPower > MAX_CAPACITY_POWER)
            throw new IllegalArgumentException("SHaRC capacity power must be in [" + MIN_CAPACITY_POWER + ", " + MAX_CAPACITY_POWER + "]");
        return new SharcRequest(version, enabled == 1, capacityPower);
    }

    public int capacity() {
        return 1 << capacityPower;
    }

    private static int literal(String source, String name) {
        var matcher = Pattern.compile("\\bconst\\s+int\\s+" + name + "\\s*=\\s*(\\d+)\\s*;").matcher(source);
        if (!matcher.find()) throw new IllegalArgumentException(name + " must be one literal const int");
        int value = Integer.parseInt(matcher.group(1));
        if (matcher.find()) throw new IllegalArgumentException("Duplicate " + name);
        return value;
    }
}
