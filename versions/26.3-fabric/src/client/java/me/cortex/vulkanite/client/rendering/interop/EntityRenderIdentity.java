package me.cortex.vulkanite.client.rendering.interop;

import java.util.UUID;

/** Identity observed during Minecraft's normal entity extraction and submission. */
public interface EntityRenderIdentity {
    UUID vulkanite$entityUuid();

    void vulkanite$entityUuid(UUID uuid);

    boolean vulkanite$cameraBody();

    void vulkanite$cameraBody(boolean cameraBody);
}
