package me.cortex.vulkanite.client.rendering.interop;

import net.minecraft.client.renderer.feature.submit.SubmitNode;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Sidecar for identity metadata on already-produced submit nodes; never submits geometry. */
public final class DynamicSubmitOrigins {
    public record Origin(UUID entityId, boolean cameraBody, Long blockEntityPos,
                         boolean itemSurface, Integer computedItemId) {
        public Origin(UUID entityId, boolean cameraBody) {
            this(entityId,cameraBody,null,false,null);
        }
    }
    public enum Kind { SURFACE, SHADOW_PATCH, NAME_TAG, TEXT, OUTLINE, GIZMO, BREAKING_OVERLAY }
    private static final Origin UNKNOWN = new Origin(null, false);
    private static final ThreadLocal<ArrayDeque<Origin>> CURRENT = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<IdentityHashMap<SubmitNode, Origin>> NODES =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private static final ThreadLocal<IdentityHashMap<SubmitNode, Kind>> NODE_KINDS =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private static final Map<Object, Kind> PHASE_KINDS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private DynamicSubmitOrigins() {}

    public static void begin() {
            ACTIVE.set(true);
            NODES.get().clear();
            NODE_KINDS.get().clear();
            CURRENT.get().clear();
    }
    public static void end() {
            ACTIVE.set(false);
            NODES.get().clear();
            NODE_KINDS.get().clear();
            CURRENT.get().clear();
    }

    public static void push(EntityRenderIdentity state) {
        if (!ACTIVE.get()) return;
        var id = state.vulkanite$entityUuid();
        CURRENT.get().push(id == null ? UNKNOWN : new Origin(id, state.vulkanite$cameraBody()));
    }

    public static void pop() {
        if (ACTIVE.get() && !CURRENT.get().isEmpty()) CURRENT.get().pop();
    }

    /** Observe the actual first-person hand submit without retaining the item. */
    public static void pushHand() {
        if (!ACTIVE.get()) return;
        CURRENT.get().push(new Origin(null,false));
    }

    /** Block position distinguishes simultaneous world surfaces across main and shadow. */
    public static void pushBlockEntity(
            net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState state) {
        if (!ACTIVE.get()) return;
        var pos=state.blockPos;
        CURRENT.get().push(pos==null?UNKNOWN:new Origin(null,false,pos.asLong(),false,null));
    }

    /** Marks the real ItemStackRenderState submit, distinct from a player's arm. */
    public static boolean pushItemSurface() {
        if (!ACTIVE.get() || CURRENT.get().isEmpty()) return false;
        var parent=CURRENT.get().peek();
        CURRENT.get().push(new Origin(parent.entityId(),parent.cameraBody(),
                parent.blockEntityPos(),true,null));
        return true;
    }

    public static void classifyPhase(Object phase, Kind kind) {
        PHASE_KINDS.put(phase, kind);
    }

    public static void remember(SubmitNode node, Object phase) {
        if (!ACTIVE.get()) return;
        NODE_KINDS.get().put(node, PHASE_KINDS.getOrDefault(phase, Kind.SURFACE));
        if (!CURRENT.get().isEmpty()) {
            var origin = CURRENT.get().peek();
            if (origin != UNKNOWN) {
                if(origin.itemSurface()) origin=new Origin(origin.entityId(),origin.cameraBody(),
                        origin.blockEntityPos(),true,
                        net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.getCurrentRenderedItem());
                NODES.get().put(node, origin);
            }
        }
    }

    public static Origin originOf(SubmitNode node) {
        return ACTIVE.get() ? NODES.get().get(node) : null;
    }

    public static Kind kindOf(SubmitNode node) {
        return ACTIVE.get() ? NODE_KINDS.get().getOrDefault(node, Kind.SURFACE) : Kind.SURFACE;
    }

}
