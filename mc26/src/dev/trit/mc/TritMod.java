package dev.trit.mc;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.PackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Trit 纹理优化 —— MC 26.3 版。
 *
 * 【定位】和 1.21.4 版最大的区别：这一版**不自带任何贴图**。
 *   1.21.4 版往 blocks atlas 里塞了 35 张 512×512，实测把 atlas 从
 *      1024×512 撑到 4096×4096 —— 那是显存涨 16× 的劣化，不是优化。
 *   26.3 版只做一件事：**运行时把玩家自己装的材质包降采样**，
 *   产出一个只含"被缩小的那部分纹理"的优化包，压在源包之上覆盖生效。
 *
 * 【TTX 在这里的角色】MC atlas 硬编码 RGBA8，纹理只能是 PNG，TTX 无法直供 GPU。
 *   所以 TTX 用作**源包的解码缓存**（体积小 3.6×、解码快 8×），
 *   重建优化包时从 TTX 恢复，避免每次重启都重解一遍源 PNG。
 *
 * 【K 键】开关：关 = 完全用回玩家原始材质包，开 = 用优化后的副本。
 */
public final class TritMod implements ClientModInitializer {

    public static final String MOD_ID = "trit";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static final Path CONFIG_PATH =
        FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + "-texture.json");

    /** 开关：true = 用优化后的材质包副本。 */
    private static boolean enabled = true;

    /** 降采样档位：纹理最长边上限。256 = 512x 的材质包压到 256x（atlas 回落，显存省约 4~16×）。 */
    private static int maxEdge = 256;

    /** 强度预设：名字 + 对应 maxEdge。按 G 键循环切换，满足"我想选择加强"。 */
    private static final String[] STRENGTH_NAMES = {"standard", "strong", "extreme"};
    private static final int[] STRENGTH_EDGES = {256, 128, 32};
    private static int strengthIndex = 1;   // 默认 strong（用户选择"加强"）

    private static KeyMapping toggleKey;
    private static KeyMapping strengthKey;
    private static volatile boolean applying = false;

    @Override
    public void onInitializeClient() {
        loadConfig();

        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.trit.toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_K,
            KeyMapping.Category.MISC));
        strengthKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.trit.strength", InputConstants.Type.KEYBOARD, InputConstants.KEY_G,
            KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (toggleKey.consumeClick()) setEnabled(client, !enabled);
            if (strengthKey.consumeClick()) cycleStrength(client);
        });

        ClientLifecycleEvents.CLIENT_STARTED.register(TritMod::syncOnStartup);

        LOG.info("[trit] MC 26.3 版已加载；K 键开关 / G 键切换强度；强度 {} (maxEdge={})；当前={}",
            STRENGTH_NAMES[strengthIndex], maxEdge, enabled ? "开" : "关");
    }

    // ------------------------------------------------------------------
    // 启动同步
    // ------------------------------------------------------------------

    private static void syncOnStartup(Minecraft client) {
        PackRepository repo = client.getResourcePackRepository();
        if (repo == null) return;

        Path gameDir = client.getResourcePackDirectory().getParent();
        if (gameDir == null) gameDir = FabricLoader.getInstance().getGameDir();

        PackOptimizer.Report rep = PackOptimizer.ensure(gameDir, maxEdge,
            new ArrayList<>(repo.getSelectedIds()));

        boolean present = rep.result == PackOptimizer.Result.REBUILT
            || rep.result == PackOptimizer.Result.UP_TO_DATE;
        String optId = findOptId(repo);
        boolean selected = optId != null && repo.getSelectedIds().contains(optId);

        if (rep.result == PackOptimizer.Result.REBUILT) {
            // 磁盘上刚生成新目录，必须重新扫描才看得见
            repo.reload();
            optId = findOptId(repo);
            selected = optId != null && repo.getSelectedIds().contains(optId);
        }

        if (enabled && !present) {
            LOG.info("[trit] 启用失败：材质包无需优化或优化未生成（result={}）", rep.result);
            return;
        }

        if (enabled == selected) {
            LOG.info("[trit] 启动同步：状态已为 {}（result={}），无需重载", enabled ? "开" : "关", rep.result);
            return;
        }

        applySelection(client, repo, enabled);
    }

    // ------------------------------------------------------------------
    // 开关
    // ------------------------------------------------------------------

    private static void setEnabled(Minecraft client, boolean on) {
        if (applying) return;
        // 幂等：已经在目标状态就不重载，避免无谓的整包重载（开关更快）
        PackRepository repo0 = client.getResourcePackRepository();
        if (repo0 != null) {
            String optId = findOptId(repo0);
            boolean selected = optId != null && repo0.getSelectedIds().contains(optId);
            if (on == selected && enabled == on) {
                notify(client, Component.literal("Trit 已处于" + (on ? "开" : "关") + "状态"));
                return;
            }
        }
        enabled = on;
        saveConfig();

        PackRepository repo = client.getResourcePackRepository();
        if (repo == null) return;

        if (on) {
            Path gameDir = client.getResourcePackDirectory().getParent();
            if (gameDir == null) gameDir = FabricLoader.getInstance().getGameDir();
            PackOptimizer.Report rep = PackOptimizer.ensure(gameDir, maxEdge,
                new ArrayList<>(repo.getSelectedIds()));
            if (rep.result == PackOptimizer.Result.NO_PACKS || rep.result == PackOptimizer.Result.FAILED) {
                notify(client, Component.translatable("message.trit.nothing"));
                return;
            }
            if (rep.result == PackOptimizer.Result.REBUILT) repo.reload();
        }

        applySelection(client, repo, on);
        notify(client, Component.translatable(on ? "message.trit.on" : "message.trit.off"));
    }

    /**
     * 循环切换强度预设（G 键）：standard(256) -> strong(128) -> extreme(32) -> standard。
     * 改变 maxEdge 会触发优化包按新档位重建（fingerprint 含 maxEdge），
     * 重建后重载资源使新档位生效。
     */
    private static void cycleStrength(Minecraft client) {
        if (applying) return;
        strengthIndex = (strengthIndex + 1) % STRENGTH_NAMES.length;
        maxEdge = STRENGTH_EDGES[strengthIndex];
        saveConfig();

        PackRepository repo = client.getResourcePackRepository();
        if (repo == null) return;
        Path gameDir = client.getResourcePackDirectory().getParent();
        if (gameDir == null) gameDir = FabricLoader.getInstance().getGameDir();

        PackOptimizer.Report rep = PackOptimizer.ensure(gameDir, maxEdge,
            new ArrayList<>(repo.getSelectedIds()));
        if (rep.result == PackOptimizer.Result.REBUILT) repo.reload();

        applySelection(client, repo, enabled);
        notify(client, Component.literal(
            "Trit 强度：" + STRENGTH_NAMES[strengthIndex] + "（maxEdge=" + maxEdge
                + "）。注：64x 包要压到 extreme(32) 才会缩小原生 64px 纹理（会变糊）"));
    }

    /** 真正改动启用集并重载。列表末尾 = 最高优先级（1.21.4 已实测，26.3 同构）。 */
    private static void applySelection(Minecraft client, PackRepository repo, boolean on) {
        String optId = findOptId(repo);
        if (optId == null) {
            LOG.warn("[trit] 找不到优化包（resourcepacks/{}），跳过", PackOptimizer.OPT_PACK_DIR_NAME);
            return;
        }
        applying = true;
        try {
            List<String> sel = new ArrayList<>(repo.getSelectedIds());
            sel.remove(optId);
            if (on) {
                if (!repo.getAvailableIds().contains(optId)) repo.addPack(optId);
                sel.add(optId);          // 末尾 = 最高优先级，盖住源包
            } else {
                repo.removePack(optId);
            }
            repo.setSelected(sel);
            client.options.updateResourcePacks(repo);
            client.reloadResourcePacks();
            LOG.info("[trit] 已{}优化包（{}）", on ? "启用" : "禁用", optId);
        } finally {
            applying = false;
        }
    }

    /**
     * 优化包的 id。MC 26.3 对目录包用 "file/<名字>" 形式的 id，
     * 不硬编码前缀，直接在可用列表里按名字匹配，避免版本间格式变化导致开关静默失效。
     */
    private static String findOptId(PackRepository repo) {
        for (String id : repo.getAvailableIds()) {
            if (id.contains(PackOptimizer.OPT_PACK_DIR_NAME)) return id;
        }
        return null;
    }

    private static void notify(Minecraft client, Component msg) {
        if (client.player != null) client.player.sendSystemMessage(msg);
        else LOG.info("[trit] {}", msg.getString());
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    private static void loadConfig() {
        try {
            if (!Files.exists(CONFIG_PATH)) return;
            String s = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            enabled = !s.contains("\"enabled\":false") && !s.contains("\"enabled\": false");

            int idx = -1;
            int si = s.indexOf("\"strength\":");
            if (si >= 0) {
                for (int n = 0; n < STRENGTH_NAMES.length; n++) {
                    if (s.contains("\"" + STRENGTH_NAMES[n] + "\"")) { idx = n; break; }
                }
            }
            if (idx < 0) {
                // 兼容旧配置：按 maxEdge 反推最接近的预设
                int i = s.indexOf("\"maxEdge\":");
                if (i < 0) i = s.indexOf("\"maxEdge\": ");
                if (i >= 0) {
                    int j = i;
                    while (j < s.length() && !Character.isDigit(s.charAt(j)) && s.charAt(j) != '-') j++;
                    int k = j;
                    while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
                    if (k > j) {
                        int me = Integer.parseInt(s.substring(j, k));
                        int best = 0, bestd = Integer.MAX_VALUE;
                        for (int m = 0; m < STRENGTH_EDGES.length; m++) {
                            int d = Math.abs(STRENGTH_EDGES[m] - me);
                            if (d < bestd) { bestd = d; best = m; }
                        }
                        idx = best;
                    }
                }
            }
            if (idx >= 0) strengthIndex = idx;
            maxEdge = STRENGTH_EDGES[strengthIndex];
        } catch (IOException e) {
            LOG.warn("[trit] 读取配置失败，用默认：开 + strong(128)", e);
        }
    }

    private static void saveConfig() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH,
                "{\"enabled\":" + enabled + ",\"strength\":\"" + STRENGTH_NAMES[strengthIndex]
                    + "\",\"maxEdge\":" + maxEdge + "}\n",
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("[trit] 写入配置失败（本次切换不持久化）", e);
        }
    }
}
