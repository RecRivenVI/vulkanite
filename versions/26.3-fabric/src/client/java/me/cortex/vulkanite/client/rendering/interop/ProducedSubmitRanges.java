package me.cortex.vulkanite.client.rendering.interop;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.renderer.feature.submit.SubmitNode;

/** Submit ranges from the normal producer for object identity and geometry ownership. */
public final class ProducedSubmitRanges {
    public record Span(
            int start,
            int end,
            DynamicSubmitOrigins.Origin origin,
            DynamicSubmitOrigins.Kind kind) {}

    public record Summary(boolean complete, List<Span> spans) {}

    private static final class Submit {
        final DynamicSubmitOrigins.Origin origin;
        final DynamicSubmitOrigins.Kind kind;
        final IdentityHashMap<BufferBuilder, Integer> starts = new IdentityHashMap<>();

        Submit(DynamicSubmitOrigins.Origin origin, DynamicSubmitOrigins.Kind kind) {
            this.origin = origin;
            this.kind = kind;
        }
    }

    private static final class State {
        final IdentityHashMap<Object, BufferBuilder> buildersByDraw = new IdentityHashMap<>();
        final IdentityHashMap<BufferBuilder, List<Span>> rangesByBuilder = new IdentityHashMap<>();
        Submit current;
    }

    private static final ThreadLocal<State> STATE = new ThreadLocal<>();

    private ProducedSubmitRanges() {}

    public static boolean enabled() {
        return STATE.get() != null;
    }

    public static void begin() {
        STATE.set(new State());
    }

    public static void end() {
        STATE.remove();
    }

    /**
     * Observe each node when the normal renderer reads it, without changing the renderer's group
     * boundaries, order, or one-call batching. The returned list has the same contents and size as
     * the producer's list.
     */
    public static <T extends SubmitNode> List<T> observeList(List<T> source) {
        if (!enabled()) return source;
        return new AbstractList<>() {
            @Override
            public T get(int index) {
                T node = source.get(index);
                try {
                    endSubmit();
                    beginSubmit(node);
                } catch (Throwable failure) {
                    ProducedDraws.fail(failure);
                    end();
                }
                return node;
            }

            @Override
            public int size() {
                return source.size();
            }
        };
    }

    public static void beginSubmit(SubmitNode node) {
        State state = STATE.get();
        if (state == null) return;
        if (state.current != null)
            throw new IllegalStateException("Nested feature buildGroup submission");
        state.current =
                new Submit(DynamicSubmitOrigins.originOf(node), DynamicSubmitOrigins.kindOf(node));
        for (var builder : state.buildersByDraw.values())
            state.current.starts.put(
                    builder, ((BufferBuilderVertexCount) builder).vulkanite$vertexCount());
    }

    public static void trackBuilder(Object draw, VertexConsumer consumer) {
        State state = STATE.get();
        if (state == null || !(consumer instanceof BufferBuilder builder)) return;
        state.buildersByDraw.put(draw, builder);
        if (state.current != null)
            state.current.starts.putIfAbsent(
                    builder, ((BufferBuilderVertexCount) builder).vulkanite$vertexCount());
    }

    public static void endSubmit() {
        State state = STATE.get();
        if (state == null || state.current == null) return;
        var submit = state.current;
        state.current = null;
        for (var entry : submit.starts.entrySet()) {
            int end = ((BufferBuilderVertexCount) entry.getKey()).vulkanite$vertexCount();
            if (end > entry.getValue())
                state.rangesByBuilder
                        .computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                        .add(new Span(entry.getValue(), end, submit.origin, submit.kind));
        }
    }

    public static Summary onMesh(Object draw, int vertexCount) {
        State state = STATE.get();
        if (state == null) return new Summary(false, List.of());
        var builder = state.buildersByDraw.get(draw);
        if (builder == null) return new Summary(false, List.of());
        var ranges = state.rangesByBuilder.remove(builder);
        if (ranges == null) ranges = new ArrayList<>();
        if (state.current != null) {
            Integer start = state.current.starts.get(builder);
            if (start != null && vertexCount > start) {
                ranges.add(new Span(start, vertexCount, state.current.origin, state.current.kind));
                state.current.starts.put(builder, 0);
            }
        }
        int expectedStart = 0;
        boolean complete = true;
        for (var range : ranges) {
            if (range.start != expectedStart || range.end < range.start || range.end > vertexCount)
                complete = false;
            expectedStart = range.end;
        }
        if (expectedStart != vertexCount) complete = false;
        return new Summary(complete, List.copyOf(ranges));
    }
}
