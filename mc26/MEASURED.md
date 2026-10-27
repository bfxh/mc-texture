# trit 纹理优化 · MC 26.3 版 · 实测记录

目标平台：`E:\MC\MC\profiles\26.3-Fabric 0.20.1`（MC 26.3 / Fabric Loader 0.20.1 / fabric-api 0.165.0+26.3 /
Java 25，另装 Sodium、Iris、Voxy、cloth-config）。

## 一、为什么从 1.21.4 迁到 26.3

| 项 | 1.21.4（旧） | 26.3（现） |
|---|---|---|
| 游戏 jar | 混淆（`fku.class`） | **未混淆**（`com/mojang/blaze3d/Blaze3D.class`） |
| 映射 | yarn + Intermediary | **Mojang 官方名，无 Intermediary** |
| 构建 | Loom 1.10.1 + Gradle 8.13 | **直接 javac（26.1+ 不需要 remapJar）** |
| Java | 21 | 25 |
| 键位 | `GLFW.GLFW_KEY_K` | `InputConstants.KEY_K`（26.3 输入已 SDL 化） |

26.1 起 Mojang 停止混淆，Fabric 同步弃用 Yarn/Intermediary。26.3 的 mod 直接用官方类名编译，
**javac 产出的 class 就是最终产物**，整个 Loom/Gradle 工具链不再必要 —— 这也让本机（无 Gradle）能直接构建。

API 迁移对照（已用 javap 逐个核对 26.3 jar）：

| 1.21.4 (yarn) | 26.3 (Mojang) |
|---|---|
| `net.minecraft.util.Identifier` | `net.minecraft.resources.Identifier` |
| `MinecraftClient` | `Minecraft` |
| `getResourcePackManager()` | `getResourcePackRepository()` → `PackRepository` |
| `getIds()` / `getEnabledIds()` / `setEnabledProfiles()` | `getAvailableIds()` / `getSelectedIds()` / `setSelected()` |
| `reloadResources()` | `reloadResourcePacks()` |
| `getAllResources(id)` / `Resource.getPackId()` | `getResourceStack(id)` / `Resource.sourcePackId()` |
| `Text.literal/translatable` | `Component.literal/translatable` |
| `KeyBinding` + `KeyBindingHelper` | `KeyMapping` + `KeyMappingHelper`（包路径改为 `client.keymapping.v1`） |
| `InputUtil.Type.KEYSYM` | `InputConstants.Type.KEYBOARD` |
| `options.refreshResourcePacks()` | `options.updateResourcePacks()` |
| `player.sendMessage(c, true)` | `player.sendSystemMessage(c)` |

## 二、1.21.4 崩溃的真正根因（顺带修好）

`fabric-biome-api-v1` 找不到 `method_30611` —— 不是 mod 冲突，是**启动器没给游戏做 remap**：
1. Axolotl 的 `meta/versions/1.21.4/1.21.4.json` 是**原版** json（mainClass = `net.minecraft.client.main.Main`，
   库表里没有 fabric-loader / sponge-mixin / intermediary）；
2. `meta/libraries` 里**根本没有 intermediary jar**，只有 `fabric-loader-0.20.1`；
3. 于是 loader 拿到的是未 remap 的混淆 jar → 所有 mixin 的 refmap 全部失配。

补齐 `net.fabricmc:intermediary:1.21.4-v2`（622 KB）+ asm 9.10.1（剔除 MC 自带的 9.6，
loader 禁止 classpath 上出现重复 ASM 类）后，1.21.4 Fabric 实例已能正常进游戏。

## 三、26.3 上的实测数据

方法：写入 `config/trit-texture.json` 改档位，用 `mc26/launch.py` 直接拉起实例，
读日志里的 `Created: WxHxM ...-atlas`。

### 基线（trit 关闭）
```
blocks.png-atlas  4096x2048     items.png-atlas  2048x1024
```
**这很重要**：4096×2048 是这套材质包（Optimum Realism 3026 张 + LRT 1999 张）的固有尺寸，
**不是 trit 造成的**。

### maxEdge = 256（默认）
```
[trit] 材质包优化完成：37 张（降采样 37 张），5,879,213 → 698,030 字节，耗时 1633 ms
[trit] 已启用优化包（file/trit-optimized）
blocks.png-atlas  4096x2048     ← 未变
```
压缩比 **8.4×**，但 atlas 不变。

### maxEdge = 64（激进）
```
[trit] 材质包优化完成：2228 张（降采样 2228 张），68,530,374 → 12,808,426 字节，耗时 18312 ms
blocks.png-atlas  4096x2048     ← 仍未变
items.png-atlas   2048x1024 → 1024x1024   ← 省 2×
```
压缩比 **5.35×**。items atlas 真的小了，blocks 没有。

### 为什么 blocks atlas 压不下来（负面结论，已验证）

blocks atlas 的尺寸由 **sprite 数量 × tile 尺寸** 决定，不是单张最大尺寸：
- 实际生效的是 **Optimum Realism 64x**（在 options.txt 里排在 LRT 之后 = 优先级更高），tile = 64；
- 约 2048 个方块 sprite × 64×64 = 8.4 M 像素 = 恰好 4096×2048；
- 降到 64 并不会减少 sprite 数量，面积需求不变 → atlas 尺寸不变；
- 要让它降一档，得把方块纹理压到 **32×**，代价是肉眼可见的糊。

**所以在这个环境里，"降采样"能兑现的是磁盘与解码时间，不是 blocks atlas 显存。**
这是 MC/Sodium atlas 机制的硬限制，不是实现问题。

## 四、当前实现

- 入口 `dev.trit.mc.TritMod`：K 键开关，配置 `config/trit-texture.json`（`enabled` / `maxEdge`）。
- `dev.trit.mc.PackOptimizer`：
  - 只读 PNG 头（IHDR）判断尺寸，**只有超过档位的纹理才进优化包**，其余继续由原包提供
    （否则优化包会退化成源包的全量副本：磁盘翻倍、构建白慢）；
  - 降采样用整数 box 平均，alpha 通道一起平均，避免透明边缘出现黑边；
  - 结果写到 `resourcepacks/trit-optimized/`，用 `repo.addPack()` + `setSelected()` 压在源包之上
    （列表末尾 = 最高优先级）；
  - TTX2 用作**源包的解码缓存**（`trit-optimize/cache/`），下次重建时从 TTX 恢复，
    省掉一次完整 PNG 解码；
  - 指纹（包名 + 大小 + mtime + 档位）不变则完全跳过，启动零开销。
- 修掉的旧 bug：`PngIO.read()` 把 alpha 恒写成 255 —— 那会毁掉玻璃/树叶/水等透明纹理。

## 五、尚未验证

- **K 键实机切换**：本机只能拉起游戏、读日志，无法模拟按键。需要你在游戏里按一次 K，
  确认行动条出现「开/关」提示且资源重载正常。
- Iris shader 下的表现（已装 Iris，但未挂 shaderpack 实测）。
