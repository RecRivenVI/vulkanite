package io.github.recrivenvi.compliance;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

record Repository(
        Path root,
        List<String> files,
        Set<String> targets,
        Set<String> components,
        Set<String> products,
        Set<String> tools,
        Set<String> instances,
        Set<String> sourceSets,
        Vocabulary vocabulary,
        boolean git) {
    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of(".git", ".gradle", ".kotlin", ".idea", ".vscode", "build", "out");
    private static final Pattern THIRD_PARTY = Pattern.compile("components/[^/]+/third_party/.*");
    private static final Pattern RESULT = Pattern.compile("validations/[^/]+/result/.*");

    static final Set<String> BUILT_IN_INSTANCES = Set.of("client", "client-multiplayer", "server");

    static Repository scan(
            Path root,
            Set<String> targets,
            Set<String> components,
            Set<String> products,
            Set<String> tools,
            Set<String> instances,
            Set<String> sourceSets,
            Vocabulary vocabulary) {
        List<String> tracked = gitFiles(root);
        return new Repository(
                root,
                tracked != null ? tracked : walk(root),
                Set.copyOf(targets),
                Set.copyOf(components),
                Set.copyOf(products),
                Set.copyOf(tools),
                Set.copyOf(instances),
                Set.copyOf(sourceSets),
                vocabulary,
                tracked != null);
    }

    boolean exists(String path) {
        return Files.isRegularFile(root.resolve(path));
    }

    byte[] bytes(String path) {
        try {
            return Files.readAllBytes(root.resolve(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String text(String path) {
        return new String(bytes(path), StandardCharsets.UTF_8);
    }

    Properties properties(String path) {
        Properties properties = new Properties();
        if (!exists(path)) return properties;
        try {
            properties.load(new StringReader(text(path)));
        } catch (IOException | IllegalArgumentException e) {
            return new Properties();
        }
        return properties;
    }

    // 验证工具写入的结果与证据保持原样，不按文本与 Markdown 规则检查（C-09）。
    static boolean result(String file) {
        return RESULT.matcher(file).matches();
    }

    List<String> filesUnder(String directory) {
        String prefix = directory + "/";
        return files.stream().filter(file -> file.startsWith(prefix)).toList();
    }

    String origin() {
        if (!git) return null;
        String output = git(root, "remote", "get-url", "origin");
        return output == null || output.isBlank() ? null : output.strip();
    }

    String gitMode(String path) {
        if (!git) return null;
        String output = git(root, "ls-files", "-s", "--", path);
        if (output == null || output.isBlank()) return null;
        return output.strip().split(" ")[0];
    }

    List<String> trackedIgnoredFiles() {
        if (!git) return List.of();
        String output = git(root, "ls-files", "--cached", "--ignored", "--exclude-standard", "-z");
        if (output == null) return List.of();
        return Stream.of(output.split("\0")).filter(entry -> !entry.isEmpty()).sorted().toList();
    }

    private static List<String> gitFiles(Path root) {
        String output = git(root, "ls-files", "--cached", "--others", "--exclude-standard", "-z");
        if (output == null) return null;
        Set<String> files = new TreeSet<>();
        for (String entry : output.split("\0"))
            if (!entry.isEmpty()
                    && Files.isRegularFile(root.resolve(entry))
                    && !THIRD_PARTY.matcher(entry).matches()) files.add(entry);
        return List.copyOf(files);
    }

    private static String git(Path root, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            process.getOutputStream().close();
            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.getErrorStream().readAllBytes();
            return process.waitFor() == 0 ? output : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static List<String> walk(Path root) {
        Set<String> files = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .filter(Repository::included)
                    .forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(files);
    }

    private static boolean included(String path) {
        for (String segment : path.split("/"))
            if (SKIPPED_DIRECTORIES.contains(segment)) return false;
        return !path.startsWith("instances/")
                && !THIRD_PARTY.matcher(path).matches()
                && !path.matches("validations/[^/]+/instance/.*")
                && !path.equals("local.toml");
    }
}
