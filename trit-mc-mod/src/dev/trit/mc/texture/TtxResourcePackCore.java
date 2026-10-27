package dev.trit.mc.texture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行时接管 MC 材质加载的【零 MC 依赖核心】——TtxResourcePack 的纯 Java 等价实现。
 *
 * 工程里 TtxResourcePack(Fabric) 依赖 Minecraft 1.21 API、本机无 gradle 无法编译验证；
 * 本类只依赖 JDK + trit-texture 解码器，可在本机完整编译运行，用于：
 *   1) 证明「TTX2 → 解码 → 按 vanilla 标准资源路径映射」的接管逻辑正确；
 *   2) 生成真实可加载的 Minecraft resourcepack 目录（解码放构建期，丢进 MC 立即见效）。
 *
 * vanilla 方块 → TTX 基名 的覆盖映射与 TritMod.VANILLA 严格一致。
 */
public final class TtxResourcePackCore {

    /** vanilla 纹理路径(无 .png 后缀) → TTX 基名。与 TritMod.VANILLA 保持一致。 */
    public static final String[][] VANILLA = {
        {"block/stone", "stone"},
        {"block/dirt", "dirt"},
        {"block/grass_block_top", "grass_block_top"},
        {"block/oak_log", "oak_log"},
        {"block/coal_ore", "coal_ore"},
        {"block/water", "water"},
        {"block/lava", "lava"},
    };

    /** labPBR 标准层后缀（与 GenPbr / buildMaterial 一致）。 */
    private static final String[] PBR_LAYERS = {"normal", "rough", "metal", "ao"};

    private TtxResourcePackCore() {}

    /**
     * 加载 + 解码 + 映射：vanilla 资源路径(minecraft:...) → 解码后的 RGBA 图。
     * 一个基名产出 albedo + 存在的 PBR 层（共 1~5 张）。
     */
    public static Map<String, TtxTextureProvider.Rgba> buildLayerMap(Path ttxDir) throws IOException {
        Map<String, TtxTextureProvider.Rgba> map = new LinkedHashMap<>();
        for (String[] m : VANILLA) {
            byte[] albedo = read(ttxDir, m[1] + ".ttx2");
            if (albedo == null) continue;
            map.put("minecraft:textures/" + m[0] + ".png", TtxTextureProvider.decode(albedo));
            for (String layer : PBR_LAYERS) {
                byte[] b = read(ttxDir, m[1] + "_" + layer + ".ttx2");
                if (b == null) continue;
                map.put("minecraft:textures/" + m[0] + "_" + layer + ".png", TtxTextureProvider.decode(b));
            }
        }
        return map;
    }

    /** RGBA → PNG 字节（零 MC 依赖，ImageIO）。 */
    public static byte[] rgbaToPng(TtxTextureProvider.Rgba img) throws IOException {
        BufferedImage bi = new BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[img.width];
        for (int y = 0; y < img.height; y++) {
            for (int x = 0; x < img.width; x++) {
                int o = (y * img.width + x) * 4;
                int r = img.rgba[o] & 0xff, g = img.rgba[o + 1] & 0xff, b = img.rgba[o + 2] & 0xff;
                row[x] = (0xff << 24) | (r << 16) | (g << 8) | b;
            }
            bi.setRGB(0, y, img.width, 1, row, 0, img.width);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (!ImageIO.write(bi, "png", bos))
            throw new IOException("no PNG writer for " + img.width + "x" + img.height);
        return bos.toByteArray();
    }

    /**
     * 生成标准 resourcepack 目录：pack.mcmeta + assets/minecraft/textures/block/*.png（含 labPBR 命名层）。
     * 该目录丢进 MC 的 resourcepacks/ 即可启用，等价「路线 A 覆盖原版图集」。
     */
    public static int writeResourcepack(Path ttxDir, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("pack.mcmeta"),
            "{\n  \"pack\": {\n    \"pack_format\": 34,\n" +
            "    \"description\": \"Trit TT3 textures (TTX2 -> PNG, MC 1.21, labPBR)\"\n  }\n}");

        Map<String, TtxTextureProvider.Rgba> map = buildLayerMap(ttxDir);
        int blocks = 0, pbr = 0;
        for (Map.Entry<String, TtxTextureProvider.Rgba> e : map.entrySet()) {
            String sub = e.getKey().substring("minecraft:".length());
            Path p = outDir.resolve("assets/minecraft/" + sub);
            Files.createDirectories(p.getParent());
            Files.write(p, rgbaToPng(e.getValue()));
            if (isPbrLayer(sub)) pbr++; else blocks++;
        }
        System.out.printf("[core] wrote resourcepack: %d block png + %d PBR png -> %s%n", blocks, pbr, outDir);
        return map.size();
    }

    /** 按文件名判断是否 PBR 层（基名含下划线的 albedo 如 grass_block_top 不能误判）。 */
    static boolean isPbrLayer(String sub) {
        return sub.endsWith("_normal.png") || sub.endsWith("_rough.png")
            || sub.endsWith("_metal.png") || sub.endsWith("_ao.png");
    }

    private static byte[] read(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        if (!Files.exists(p)) return null;
        return Files.readAllBytes(p);
    }
}
