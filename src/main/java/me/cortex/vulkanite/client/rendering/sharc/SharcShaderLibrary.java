package me.cortex.vulkanite.client.rendering.sharc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SharcShaderLibrary {
    private static final String ROOT = "/assets/vulkanite/shaders/include/sharc/";
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*#include\\s+\"([^\"]+)\"\\s*$");

    private SharcShaderLibrary() {}

    static String loadExpanded(String name) {
        return expand(name, new HashSet<>());
    }

    private static String expand(String name, HashSet<String> stack) {
        if (!stack.add(name)) throw new IllegalArgumentException("Recursive SHaRC include: " + name);
        String source;
        try (var input = SharcShaderLibrary.class.getResourceAsStream(ROOT + name)) {
            if (input == null) throw new IllegalArgumentException("Missing SHaRC shader resource: " + name);
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new RuntimeException("Could not read SHaRC shader resource " + name, error);
        }
        Matcher matcher = INCLUDE.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(result, Matcher.quoteReplacement(expand(matcher.group(1), stack)));
        matcher.appendTail(result);
        stack.remove(name);
        return result.toString();
    }
}
