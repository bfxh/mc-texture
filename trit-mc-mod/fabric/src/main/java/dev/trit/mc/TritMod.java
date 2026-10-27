package dev.trit.mc;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil.Type;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Trit Texture Mod —— MC 材质接管 + 游戏内 L 键开关。
 *
 * 路线 A（当前唯一生效路径）：把由 TTX 生成的烘焙资源包注册为内置包，
 *   位于 resources/resourcepacks/trit_textures/，覆盖原版
 *   assets/minecraft/textures/block/*。
 *
 * 【为什么必须是 NORMAL 而不是 ALWAYS_ENABLED】
 *   原先用 ALWAYS_ENABLED：启动即强制生效，但 ResourcePackManager 每次扫描都会
 *   把它强行塞回启用列表，enable()/disable() 对它无效 —— 那样根本做不了运行时开关。
 *   改成 NORMAL 后它成为可选包，才能用 manager.enable/disable + reloadResources 切换。
 *   代价：默认不再自动启用，故在 CLIENT_STARTED 做一次状态同步（见 syncOnStartup）。
 *
 * 【已移除 - 路线 B】此前把 TTX 解码后注册到 TextureManager 的 trit:tex/* 。
 *   该命名空间游戏内无任何引用方，纯属把 ~7MB 纹理上传进显存却永不渲染（净负收益）。
 *
 * 【未启用 - 路线 A-运行时】真正的运行时解码需要动态 ResourcePack 注入；
 *   1.21.4 的 registerBuiltinResourcePack 只有 ModContainer 重载、无 Supplier<ResourcePack>，
 *   只能走 ResourcePackProfile + registerProvider，而后者必须在 pack 扫描前调用，
 *   onInitializeClient 时机不可靠。故保持路线 A 烘焙包为可靠路径。
 */
public final class TritMod implements ClientModInitializer {
    public static final String MOD_ID = "trit";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** 内置资源包的 Identifier；运行时按 "trit" 关键字在 profile 列表里定位它。 */
    private static final Identifier PACK_ID = Identifier.of(MOD_ID, "trit_textures");

    private static final Path CONFIG_PATH =
        FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + "-texture.json");

    /** 开关状态：true = 接管原版贴图，false = 用回原版。持久化到 config 目录。 */
    private static boolean enabled = true;

    /**
     * 优先级模式。false（默认）= 最低优先级，只补玩家材质包没有的纹理，**不覆盖**玩家已装的包；
     * true = 最高优先级，trit 覆盖一切（想要"强制看 trit 效果"时开）。
     */
    private static boolean highPriority = false;

    private static KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        loadState();

        FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(container ->
            ResourceManagerHelper.registerBuiltinResourcePack(
                PACK_ID,
                container,
                Text.literal("Trit 纹理优化 (TTX)"),
                ResourcePackActivationType.NORMAL));   // 必须 NORMAL，否则无法运行时开关

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.trit.toggle", Type.KEYSYM, GLFW.GLFW_KEY_K, "key.category.trit"));

        // 按键切换
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (toggleKey.wasPressed()) {
                setEnabled(client, !enabled);
            }
        });

        // 启动同步：NORMAL 包默认不启用，这里把持久化状态落到实际生效状态
        ClientLifecycleEvents.CLIENT_STARTED.register(TritMod::syncOnStartup);

        LOG.info("[trit] 内置资源包已注册（NORMAL，可运行时开关），L 键切换；当前状态={}",
            enabled ? "开" : "关");
    }

    // ------------------------------------------------------------------
    // 切换
    // ------------------------------------------------------------------

    private static void setEnabled(MinecraftClient client, boolean on) {
        enabled = on;
        saveState();
        apply(client, on);

        // 材质包优先时 trit 可能被更高优先级的包完全遮挡 —— 明确告诉玩家，免得以为开关坏了
        Text msg;
        if (on && effectivePackId(client) != null && !isTrit(effectivePackId(client))) {
            msg = Text.translatable("message.trit.shadowed", effectivePackId(client));
        } else {
            msg = Text.translatable(on ? "message.trit.on" : "message.trit.off");
        }
        if (client.player != null) client.player.sendMessage(msg, true); // true = 行动条
        LOG.info("[trit] 切换为 {}，已触发资源重载", on ? "开" : "关");
    }

    /**
     * 启动同步：NORMAL 包默认不启用，这里把持久化状态落到实际生效状态。
     *
     * 【性能要点】早期版本这里无条件 reloadResources()，导致每次启动都多跑一整轮
     *   资源重载（原版 1039 张 + 本包大图重烘焙，实测约 0.6~1.4 s 且显存再传一遍）。
     *   现改为先探测资源管理器当前状态，只有"已加载但与期望不符"才重载：
     *     NOT_LOADED_YET -> 交给即将发生的首次加载，不重载
     *     APPLIED        -> 已经生效，不重载
     *     LOADED_BUT_NOT -> 才真正重载
     *   探测失败一律按 NOT_LOADED_YET 处理（宁可不重载，也不重复付代价）。
     */
    private static void syncOnStartup(MinecraftClient client) {
        ResourcePackManager manager = client.getResourcePackManager();
        String id = findPackId(manager);
        if (id == null) {
            LOG.warn("[trit] 启动同步失败：未在 profile 列表中找到内置包，现有 id={}",
                String.join(",", manager.getIds()));
            return;
        }

        // 【判定必须以"启用集"为准，不能用"是否生效"】
        // 材质包优先时 trit 在栈里但被上层包挡住，tritAppliedState 恒为 LOADED_BUT_NOT，
        // 若拿它当依据会导致每次启动都误判"需要变更"进而白跑一次完整重载。
        boolean currentlyOn = manager.getEnabledIds().contains(id);
        if (currentlyOn == enabled) {
            String top = effectivePackId(client);
            if (enabled && top != null && !isTrit(top)) {
                LOG.info("[trit] 已启用，但 stone 当前由更高优先级的包「{}」提供 —— "
                    + "材质包优先模式下 trit 只补该包没有的纹理，属预期行为", top);
            } else {
                LOG.info("[trit] 启动同步：状态已为 {}，无需改动", enabled ? "开" : "关");
            }
            return;
        }

        int state = tritAppliedState(client);
        manager.setEnabledProfiles(buildOrderedIds(manager, id, enabled));
        syncOptions(client, manager);

        // 资源尚未加载 -> 交给即将发生的首次加载，不额外付一次重载代价
        if (state == ST_NOT_LOADED_YET) {
            LOG.info("[trit] 启动同步：{}（资源未加载，交给首次加载）", enabled ? "启用" : "禁用");
        } else {
            client.reloadResources();
            LOG.info("[trit] 启动同步：{}，已重载", enabled ? "启用" : "禁用");
        }
    }

    /** 按优先级模式排好启用顺序：默认把 trit 放最低（仅高于 vanilla），不覆盖玩家已装的包。 */
    private static List<String> buildOrderedIds(ResourcePackManager manager, String id, boolean on) {
        List<String> ordered = new ArrayList<>();
        for (String e : manager.getEnabledIds()) {
            if (!e.equals(id)) ordered.add(e);
        }
        if (on) {
            if (highPriority) ordered.add(id); else ordered.add(0, id);
        }
        return ordered;
    }

    /**
     * 写回 options.txt。refreshResourcePacks 会 clear() 掉两个列表再按启用集重建，
     * 导致"已安装但未启用"的不兼容包记录丢失 —— 这里先备份再补回。
     */
    private static void syncOptions(MinecraftClient client, ResourcePackManager manager) {
        List<String> savedIncompat = new ArrayList<>(client.options.incompatibleResourcePacks);
        client.options.refreshResourcePacks(manager);
        for (String s : savedIncompat) {
            if (!client.options.incompatibleResourcePacks.contains(s)
                    && !client.options.resourcePacks.contains(s)) {
                try {
                    client.options.incompatibleResourcePacks.add(s);
                } catch (RuntimeException ignored) {
                    // 列表不可变时放弃，不影响功能
                }
            }
        }
    }

    /** 资源管理器尚未加载（或不可用）。 */
    private static final int ST_NOT_LOADED_YET = 0;
    /** 已加载，且 trit 资源已在生效链上。 */
    private static final int ST_APPLIED = 1;
    /** 已加载，但生效链上没有 trit。 */
    private static final int ST_LOADED_BUT_NOT = 2;

    /**
     * 返回 stone.png 当前**真正生效**的那个包 id；资源没加载好则返回 null。
     * 【注意】不能只看 getAllResources 里"有没有 trit" —— 材质包优先时 trit 就在栈里
     *   但被上层包挡住，那样判断会误报"已生效"。必须只看最高优先级那一个。
     */
    private static String effectivePackId(MinecraftClient client) {
        try {
            List<Resource> all = client.getResourceManager()
                .getAllResources(Identifier.of("minecraft", "textures/block/stone.png"));
            if (all == null || all.isEmpty()) return null;
            return all.get(0).getPackId();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isTrit(String packId) {
        return packId != null && packId.contains(MOD_ID);
    }

    private static int tritAppliedState(MinecraftClient client) {
        String top = effectivePackId(client);
        if (top == null) return ST_NOT_LOADED_YET;
        return isTrit(top) ? ST_APPLIED : ST_LOADED_BUT_NOT;
    }

    /**
     * 真正改动 ResourcePackManager 的启用集并触发重载。
     *
     * 【为什么不用 manager.enable(id)】
     *   反编译确认 ResourcePackManager.enable 的实现是
     *     enabled = Lists.newArrayList(enabled); enabled.add(profile);
     *   即**追加到末尾**。而 createResourcePacks() 直接按 enabled 原序建栈（无 reverse），
     *   buildEnabledProfiles 又是保序的（streamProfilesById -> toArrayList），
     *   vanilla 作为 isRequired 的包被 InsertionPosition.BOTTOM 插到最底 ——
     *   结论：**列表末尾 = 最高优先级**。用 enable() 会让 trit 盖掉玩家已装的所有材质包。
     *   这里改用 setEnabledProfiles(有序列表)，把 trit 放在最低位（仅高于 vanilla），
     *   只在玩家材质包没有的纹理上生效，不破坏既有配置。
     *
     * 【为什么不能直接调 refreshResourcePacks】
     *   它会先 clear() 掉 resourcePacks 和 incompatibleResourcePacks 再按 enabled 重建，
     *   于是"已安装但未启用的不兼容包"记录会被丢掉。这里先备份、再补回。
     */
    private static void apply(MinecraftClient client, boolean on) {
        ResourcePackManager manager = client.getResourcePackManager();
        String id = findPackId(manager);
        if (id == null) {
            LOG.warn("[trit] apply 失败：找不到内置包 profile");
            return;
        }

        manager.setEnabledProfiles(buildOrderedIds(manager, id, on));
        syncOptions(client, manager);
        client.reloadResources();
        LOG.info("[trit] 已{}（优先级={}），触发资源重载",
            on ? "启用" : "禁用", highPriority ? "最高(覆盖玩家包)" : "最低(不覆盖玩家包)");
    }

    /**
     * 定位内置包的 profile id。
     * 不硬编码 Fabric 的内部命名格式（不同版本可能是 "trit:trit_textures" 或 "fabric/trit..."），
     * 改为在 id 列表里按 "trit" 关键字匹配，避免版本间格式差异导致开关静默失效。
     */
    private static String findPackId(ResourcePackManager manager) {
        // Fabric 用 Identifier.toString() 作为包 id（已核对 ResourceManagerHelperImpl 字节码：
        // ModNioResourcePack.create(id.toString(), ...)），即 "trit:trit_textures"。
        // 先精确匹配，再按关键字回退，避免 Fabric 内部命名在版本间变化时开关静默失效。
        String exact = PACK_ID.toString();
        if (manager.getIds().contains(exact)) return exact;
        for (String id : manager.getIds()) {
            if (id.contains(MOD_ID)) return id;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 持久化（不引第三方 JSON 库，手写固定格式）
    // ------------------------------------------------------------------

    private static void loadState() {
        enabled = true;
        highPriority = false;
        try {
            if (!Files.exists(CONFIG_PATH)) return;
            String s = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            enabled = !s.contains("\"enabled\":false") && !s.contains("\"enabled\": false");
            highPriority = s.contains("\"highPriority\":true") || s.contains("\"highPriority\": true");
        } catch (IOException e) {
            LOG.warn("[trit] 读取配置失败，回退默认：开 + 最低优先级", e);
        }
    }

    private static void saveState() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH,
                "{\"enabled\":" + enabled + ",\"highPriority\":" + highPriority + "}\n",
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("[trit] 写入配置失败（本次切换不会持久化）", e);
        }
    }
}
