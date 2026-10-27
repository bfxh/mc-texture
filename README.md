# Trit 512x MC 高纹理材质测试包（资源包 / Mod 纹理优化演示）

## 说明
本资源包独立于 `bfxh/trit` 库之外，**不修改、不依赖编译 trit 库**，仅“取所需”参考 `trit-texture` 的 PNG↔TTX/TTX2 块纹理容器能力，做 MC（Minecraft Java 版）高纹理材质优化演示。

- 目标版本：Minecraft Java 1.21（pack_format 34）
- 高纹分辨率：512×512（原版 16×16 的 32 倍线性分辨率）
- 纹理优化演示：用 `trit-texture` 的 `ttx-convert`（`png` feature）把贴图编码为 TTX1/TTX2（TT3，4×10 块，每纹素 1 trit，固定 2.4 bpp，GPU 友好随机访问），并做往返 PNG 还原校验。

## 目录
```
pack.mcmeta                 # 资源包元文件（pack_format 34）
pack.png                    # （可选）资源包图标
assets/minecraft/textures/block/   # 方块高纹贴图 512×512
assets/minecraft/textures/item/    # 物品高纹贴图（预留）
assets/minecraft/textures/entity/  # 实体高纹贴图（预留）
assets/minecraft/textures/gui/     # GUI 高纹贴图（预留）
opt/ttx/                    # trit-texture 压缩演示产物（不供 MC 直接读取）
```

## 贴图列表（均 512×512 PNG）
- stone / dirt / grass_block_top / oak_log / coal_ore / water / lava

## 压缩演示数据（trit-texture TT3，512×510 块对齐）
| 贴图 | PNG(512×512) | TTX1 | TTX2 | TTX1 比 | TTX2 比 | 往返还原 |
|---|---|---|---|---|---|---|
| stone | 84584 | 78353 | 78364 | 1.08× | 1.08× | OK |
| dirt | 76776 | 78353 | 78364 | 0.98× | 0.98× | OK |
| grass_block_top | 76384 | 78353 | 78364 | 0.97× | 0.97× | OK |
| oak_log | 85791 | 78353 | 78364 | 1.09× | 1.09× | OK |
| coal_ore | 75479 | 78353 | 78364 | 0.96× | 0.96× | OK |
| water | 2203 | 78353 | 78364 | 0.03× | 0.03× | OK |
| lava | 2203 | 78353 | 78364 | 0.03× | 0.03× | OK |

TTX1/TTX2 大小固定（固定块率 2.4 bpp + 容器头/校验尾），复杂图略优于 PNG，简单图远差于 PNG；适合 GPU 纹理容器而非通用熵压缩。

## 使用
1. 把 `pack/` 目录（含 `pack.mcmeta` 与 `assets/`）打包为 `.zip`，`pack.mcmeta` 与 `assets` 必须在 zip 根目录。
2. 放入 Minecraft `resourcepacks` 文件夹，在游戏内资源包选项中启用。
3. `opt/ttx/` 仅为 trit-texture 压缩演示产物，不放入资源包 zip。

## 边界
- 未改动 `D:/KF/trit` 任何文件；trit-texture 仅作为只读外部工具调用（`cargo build`/`ttx-convert`）。
- 高纹素材为本地程序生成的测试图，非官方 MC 资源，仅用于纹理优化方案验证。
