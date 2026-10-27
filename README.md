# mc-texture —— Minecraft 纹理优化（Fabric Mod）

以 Fabric Mod 形式在**运行时优化你已经装好的材质包**，而不是替换成另一套材质。

- 主线目标环境：**Minecraft Java 26.3 + Fabric Loader 0.20.1 + fabric-api 0.165.0+26.3 + Java 25**
- 核心手段：扫描已启用材质包，仅对超过 `maxEdge` 的纹理做降采样，产物写进一个**优先级最低**的 `trit-optimized` 资源包（只覆盖超限纹理，其余仍由你的原包提供）
- 运行时开关：**K 键**
- 纹理容器：TTX/TTX2 三值块压缩（4×8 块，每纹素 1 trit），用于缓存与 GPU 直采样路径

## 目录

```
mc26/                       # 主线工程（26.3，Java mod）
  src/dev/trit/mc/
    TritMod.java            # 入口：K 键、配置、资源包优先级、启动自检
    PackOptimizer.java      # 材质包扫描 / 降采样 / 指纹缓存
    texture/TritCodec.java  # TTX/TTX2 编解码（块常量 4×8）— Rust 的逐字节移植
    texture/TritEncoder.java
    texture/PngIO.java      # PNG 读写（保留 alpha）+ 降采样
    texture/TtxTextureProvider.java
  resources/fabric.mod.json
  build.sh                  # javac 直编 + 打 jar
  launch.py                 # 拉起任意 Axolotl Fabric 实例做验证
  MEASURED.md               # 实机对照实测数据
rust/                       # TTX 格式参考编解码器（Rust，可独立构建）
  Cargo.toml                # workspace：trit-core + trit-texture
  trit-core/                # 三值逻辑核心（Kleene/Lukasiewicz 语义、位并行）
  trit-texture/             # TTX1/TTX2/TT3 容器 + 4×8 块压缩（本仓库的编码真相源）
  crosscheck/               # 跨语言字节等价验证：Java 编码 → hex，Rust 测试断言相等
trit-mc-mod/                # 旧工程（MC 1.21.4），保留作参考
gpu/ttx_sample.glsl         # GPU 端整数域解码器（纯 GLSL，无采样依赖）
verify/                     # 验证脚本（解码比对 / 性能基准 / 打包校验）
```

## 为什么不需要 Gradle / Loom

**26.1 起 Mojang 停止混淆，Fabric 已弃用 Yarn / Intermediary。**
26.3 的 mod 直接用 Mojang 官方类名编译，`javac` 产出即最终产物，**不再需要 Loom 的 `remapJar`**。

## Rust 参考编解码器（TTX 格式的真相源）

`rust/` 是从 [`bfxh/trit`](https://github.com/bfxh/trit)（引擎 monorepo，分支
`yan/texture-optimization`）原样 vendored 的 **TTX1 / TTX2 / TT3** 三值块压缩编解码器，
作为本 mod 的**参考实现**：

- `mc26/` 里的 Java `TritCodec` / `TritEncoder` 是它的**逐字节移植**——
  块结构（4×8 = 32 纹素）、RGB565 端点对、每纹素 1 trit 的 3 级调色板、
  5 trit/字节的低位在前打包、TTX1/TTX2 容器布局、GF(3) CRC-3 / CRC-16 生成子，
  **全部与 Rust 一致**。
- 因此 mod 运行时产出的 TTX2 能被 Rust 精确解回，反之亦然。
- 这条契约由 `rust/trit-texture` 里的 `java_encoder_is_byte_identical` 测试**硬锁定**：
  同一 8×8 缓冲，Rust 编码的字节必须与 `rust/crosscheck/CrossCheck.java`
  打印的 Java 基准完全相同，任何 Java 侧字节漂移都会立即让测试变红。

构建与验证（需要 Rust 工具链）：

```bash
cd rust
cargo test -p trit-texture      # 39 测试通过（含跨语言字节等价）
cargo clippy -p trit-texture    # 零告警
```

**同步规则**：改了 `trit-texture` 的字节格式，必须同时改 `mc26/` 的 Java 移植，
并重跑跨语言比对；上游 `bfxh/trit` 仍是引擎侧的真相源。

> 说明：本仓库的 `rust/` 是 mod 项目内自带、可独立 `cargo test` 的一份副本，
> 便于在没有完整引擎 monorepo 的情况下验证格式与做离线 TTX 工具（`ttx-convert`
> 需要 `--features png`）。

构建：

```bash
cd mc26
bash build.sh     # 产出 build/jar/trit-texture-0.2.0.jar
```

`build.sh` 依赖 `cp.txt`：classpath 由本地 MC 实例的 `meta/versions/<ver>/<ver>.json`
的 libraries + fabric-api 子 jar 拼出。fabric-api 是 nested jar，需先把
`META-INF/jars/*.jar` 解压到 `mc26/deps/`。

## 部署

1. 把 `build/jar/trit-texture-0.2.0.jar` 放进实例的 `mods/`
2. 配置 `config/trit-texture.json`：

```json
{ "enabled": true, "maxEdge": 256 }
```

- `enabled:false` → 完全不介入（基线对照用）
- `maxEdge:256` → 只降采样长边 >256 的纹理（保守，肉眼无感）
- `maxEdge:64` → 激进档

## 实测（26.3 实机，Optimum Realism 64x + 其它包）

| 档位 | 处理 | 磁盘 | 耗时 | atlas 变化 |
|---|---|---|---|---|
| 基线（trit 关） | — | — | — | blocks `4096×2048`，items `2048×1024` |
| maxEdge=256 | 37 张 | 5.88 MB → 0.70 MB（8.4×） | 1.6 s | 无变化 |
| maxEdge=64 | 2228 张 | 68.5 MB → 12.8 MB（5.35×） | 18.3 s | items `2048×1024`→`1024×1024`，blocks 不变 |

TTX 容器本身（Optimum Realism R4.2.0，400 张 64×64 真实手绘纹理）：
体积 **0.28×** 于 PNG（小 3.6 倍），解码 **8.05×** 快于 PNG（59 ms vs 473 ms）。

### 已知硬限制（不是实现问题）

**blocks atlas 降不下来。** 它的尺寸 = sprite **数量** × tile 尺寸，降采样不减少 sprite 数量，
面积需求就不变。生效的 Optimum Realism 64x 约 2048 个方块 sprite × 64×64 ≈ 8.4M 像素 = 恰好 `4096×2048`。
要降一档必须压到 32×（肉眼可见糊）。所以降采样在当前环境兑现的是**磁盘与解码时间**，不是 blocks 显存。

## 未验证

- K 键实机切换（开发机无法模拟按键）
- Iris 挂 shaderpack 后的表现
- GPU 直采样路径（需自控管线，vanilla 的 atlas 硬编码 RGBA8）

详见 [`mc26/MEASURED.md`](mc26/MEASURED.md)。
