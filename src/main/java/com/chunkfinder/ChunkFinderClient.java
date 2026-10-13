package com.chunkfinder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Client-only visible-world scanner. It only inspects chunks the client has loaded;
 * it does not query server-only or unloaded terrain.
 */
public final class ChunkFinderClient implements ClientModInitializer {
    private static final int PURPLE = 0xFFB45CFF;
    private static final int PANEL = 0xD9161025;
    private static final int PANEL_LINE = 0xFF7131A8;
    private static final int WHITE = 0xFFF4EDFF;
    private static final int MUTED = 0xFFBBAEC9;

    private static KeyBinding toggleKey;
    private static KeyBinding settingsKey;
    private static boolean enabled = true;
    private static boolean showSettings = false;
    private static int sensitivity = 3; // 1..5
    private static int scanRadius = 4;  // chunks from player, capped by client's view distance
    private static int ticksSinceScan = 0;
    private static List<Hit> hits = new ArrayList<>();

    private record Hit(BlockPos pos, String label, int score, double distance) { }

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chunkfinder.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H,
                KeyBinding.Category.MISC));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chunkfinder.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J,
                KeyBinding.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                if (client.player != null) client.player.sendMessage(Text.literal("[Chunk Finder] " + (enabled ? "Enabled" : "Disabled")), true);
            }
            while (settingsKey.wasPressed()) {
                showSettings = !showSettings;
                if (client.player != null) client.player.sendMessage(Text.literal("[Chunk Finder] " + (showSettings ? "Settings opened: use K/L for sensitivity, N/M for radius" : "Settings hidden")), true);
            }
            // Lightweight keyboard adjustments while the settings hint is open.
            if (showSettings && client.getWindow() != null) {
                if (InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_K)) { sensitivity = Math.max(1, sensitivity - 1); showSettings = false; }
                if (InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_L)) { sensitivity = Math.min(5, sensitivity + 1); showSettings = false; }
                if (InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_N)) { scanRadius = Math.max(1, scanRadius - 1); showSettings = false; }
                if (InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_M)) { scanRadius = Math.min(12, scanRadius + 1); showSettings = false; }
            }
            if (enabled && client.world != null && client.player != null && ++ticksSinceScan >= 30) {
                ticksSinceScan = 0;
                hits = scan(client.world, client.player.getBlockPos(), scanRadius, sensitivity, client.options.getViewDistance().getValue());
            }
        });

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.of("chunkfinder", "overlay"),
                (drawContext, renderTickCounter) -> renderHud(drawContext, MinecraftClient.getInstance()));
    }

    private static List<Hit> scan(World world, BlockPos origin, int radius, int sensitivity, int viewDistance) {
        List<Hit> found = new ArrayList<>();
        int effectiveRadius = Math.min(radius, Math.max(2, viewDistance));
        ChunkPos center = new ChunkPos(origin);
        int verticalStep = sensitivity >= 5 ? 1 : (sensitivity >= 4 ? 2 : 4);
        int blockStep = sensitivity >= 5 ? 1 : (sensitivity >= 3 ? 2 : 4);
        int yMin = Math.max(world.getBottomY(), origin.getY() - (sensitivity >= 4 ? 64 : 32));
        int yMax = Math.min(world.getTopYInclusive(), origin.getY() + (sensitivity >= 4 ? 64 : 32));

        for (int cx = center.x - effectiveRadius; cx <= center.x + effectiveRadius; cx++) {
            for (int cz = center.z - effectiveRadius; cz <= center.z + effectiveRadius; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;
                int minX = cx << 4, minZ = cz << 4;
                for (int x = minX; x < minX + 16; x += blockStep) {
                    for (int z = minZ; z < minZ + 16; z += blockStep) {
                        for (int y = yMin; y <= yMax; y += verticalStep) {
                            BlockPos pos = new BlockPos(x, y, z);
                            BlockState state = world.getBlockState(pos);
                            String label = classify(state.getBlock());
                            if (label == null) continue;
                            double distance = Math.sqrt(pos.getSquaredDistance(origin));
                            int score = label.equals("Amethyst geode marker") ? 5 : 2;
                            found.add(new Hit(pos.toImmutable(), label, score, distance));
                        }
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(Hit::distance));
        // De-duplicate scan-step neighbors and show only the nearest items in the HUD.
        return found.stream().limit(8).toList();
    }

    private static String classify(Block block) {
        Identifier id = Registries.BLOCK.getId(block);
        String path = id.getPath();
        // These blocks are markers, not proof of player activity. Geodes contain many
        // blocks; exposed blocks only appear if in a loaded chunk and within scan range.
        if (path.equals("amethyst_block") || path.equals("budding_amethyst")) return "Amethyst geode marker";
        if (path.equals("chest") || path.equals("trapped_chest") || path.equals("barrel")) return "Container marker";
        if (path.equals("spawner")) return "Spawner marker";
        if (path.equals("ancient_debris")) return "Rare block marker";
        return null;
    }

    private static void renderHud(DrawContext ctx, MinecraftClient client) {
        if (!enabled || client.player == null || client.world == null) return;
        int x = 10, y = 10, width = 224;
        int rows = Math.min(5, hits.size());
        int height = 39 + rows * 14 + (showSettings ? 46 : 0);
        ctx.fill(x, y, x + width, y + height, PANEL);
        ctx.fill(x, y, x + width, y + 2, PANEL_LINE);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("CHUNK FINDER").styled(s -> s.withColor(PURPLE)), x + 10, y + 8, PURPLE);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("VISUALS  /  " + (enabled ? "ACTIVE" : "OFF")), x + 10, y + 20, MUTED);
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("Sensitivity " + sensitivity + "/5  •  Radius " + scanRadius + " chunks"), x + 10, y + 31, WHITE);
        int rowY = y + 46;
        if (hits.isEmpty()) {
            ctx.drawTextWithShadow(client.textRenderer, Text.literal("No markers in loaded scan area"), x + 10, rowY, MUTED);
        } else {
            for (int i = 0; i < rows; i++) {
                Hit hit = hits.get(i);
                String text = hit.label() + "  " + hit.pos().getX() + " " + hit.pos().getY() + " " + hit.pos().getZ();
                ctx.drawTextWithShadow(client.textRenderer, Text.literal(text), x + 10, rowY + i * 14, i == 0 ? WHITE : MUTED);
            }
        }
        if (showSettings) {
            int sy = y + 47 + rows * 14;
            ctx.drawTextWithShadow(client.textRenderer, Text.literal("K/L: sensitivity -/+"), x + 10, sy, MUTED);
            ctx.drawTextWithShadow(client.textRenderer, Text.literal("N/M: scan radius -/+"), x + 10, sy + 13, MUTED);
            ctx.drawTextWithShadow(client.textRenderer, Text.literal("H: toggle   J: hide this hint"), x + 10, sy + 26, MUTED);
        }
    }
}
