package dev.trit.mc.texture;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * 从 albedo PNG 派生 PBR 层（示例/demo 数据，非美术手绘）：
 *  - normal：亮度作高度场 → Sobel 梯度 → 切线空间法线（R,G,B）
 *  - rough ：3×3 局部亮度方差 → 灰度粗糙度（R=G=B）
 *  - metal ：常量 0（介电质，labPBR 约定）
 *  - ao    ：常量 255（无遮蔽）
 * 各层编码为 TTX2（与 albedo 同源 512×510），写入 opt/ttx 供 buildMaterial 接管。
 */
public final class GenPbr {

    private static final List<String> NAMES = Arrays.asList(
        "stone", "dirt", "grass_block_top", "oak_log", "coal_ore", "water", "lava");
    private static final double NORMAL_STRENGTH = 2.0;

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0]
            : "D:/KF/.yanagent/mc-texture/pack/opt/ttx";
        new File(dir).mkdirs();

        int totalBefore = 0, totalAfter = 0;
        for (String name : NAMES) {
            PngIO.Rgba albedo = PngIO.read(dir + "/" + name + "_512x512.png");
            int w = albedo.width, h = albedo.height;
            byte[] lum = new byte[w * h];
            for (int i = 0; i < w * h; i++) {
                int r = albedo.rgba[i * 4] & 0xff;
                int g = albedo.rgba[i * 4 + 1] & 0xff;
                int b = albedo.rgba[i * 4 + 2] & 0xff;
                lum[i] = (byte) (Math.round(0.299 * r + 0.587 * g + 0.114 * b));
            }

            byte[] normal = buildNormal(lum, w, h);
            byte[] rough = buildRough(lum, w, h);
            byte[] metal = solid(0, w, h);
            byte[] ao = solid(255, w, h);

            int before = (w * h * 4) * 4; // 4 个 PBR 层原始 RGBA
            int after = 0;
            after += writeLayer(dir, name, "_normal", normal, w, h);
            after += writeLayer(dir, name, "_rough", rough, w, h);
            after += writeLayer(dir, name, "_metal", metal, w, h);
            after += writeLayer(dir, name, "_ao", ao, w, h);
            totalBefore += before;
            totalAfter += after;
            System.out.printf("%-16s %dx%d  PBR 原=%6d B  压缩后=%6d B  (%.2f bpp)%n",
                name, w, h, before, after, (double) after * 8 / (w * h));
        }
        System.out.printf("%n==== PBR 层生成完成：原=%d B  压缩后=%d B  压缩比=%.2f× ====%n",
            totalBefore, totalAfter, (double) totalBefore / totalAfter);
    }

    static int writeLayer(String dir, String name, String suffix, byte[] rgba, int w, int h) throws Exception {
        byte[] ttx2 = TritEncoder.rgbaToTtx2Mipped(rgba, w, h);
        Files.write(new File(dir, name + suffix + ".ttx2").toPath(), ttx2);
        return ttx2.length;
    }

    /** 高度场（亮度）→ 切线空间法线。R,G 存 xy，B 存 z。 */
    static byte[] buildNormal(byte[] lum, int w, int h) {
        byte[] out = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int xl = Math.max(0, x - 1), xr = Math.min(w - 1, x + 1);
                int yt = Math.max(0, y - 1), yb = Math.min(h - 1, y + 1);
                int dx = (lum[y * w + xr] & 0xff) - (lum[y * w + xl] & 0xff);
                int dy = (lum[yb * w + x] & 0xff) - (lum[yt * w + x] & 0xff);
                double nx = -dx * NORMAL_STRENGTH;
                double ny = -dy * NORMAL_STRENGTH;
                double nz = 255.0; // 缩放后归一化
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                int r = clamp255((int) (0.5 * (nx / len) * 255 + 127.5));
                int g = clamp255((int) (0.5 * (ny / len) * 255 + 127.5));
                int b = clamp255((int) (0.5 * (nz / len) * 255 + 127.5));
                int off = (y * w + x) * 4;
                out[off] = (byte) r; out[off + 1] = (byte) g; out[off + 2] = (byte) b; out[off + 3] = (byte) 255;
            }
        }
        return out;
    }

    /** 3×3 局部亮度方差 → 灰度粗糙度（细节多=粗糙）。 */
    static byte[] buildRough(byte[] lum, int w, int h) {
        byte[] out = new byte[w * h * 4];
        int maxVar = 1;
        // 先算全局最大方差用于归一化
        int[] vars = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                long sum = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int sx = clampRange(x + dx, 0, w - 1), sy = clampRange(y + dy, 0, h - 1);
                        sum += lum[sy * w + sx] & 0xff;
                    }
                int mean = (int) (sum / 9);
                long v = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int sx = clampRange(x + dx, 0, w - 1), sy = clampRange(y + dy, 0, h - 1);
                        int d = (lum[sy * w + sx] & 0xff) - mean;
                        v += d * d;
                    }
                int var = (int) (v / 9);
                vars[y * w + x] = var;
                if (var > maxVar) maxVar = var;
            }
        }
        for (int i = 0; i < w * h; i++) {
            int val = 40 + (int) (200.0 * vars[i] / maxVar); // [40,240]
            val = clamp255(val);
            int off = i * 4;
            out[off] = (byte) val; out[off + 1] = (byte) val; out[off + 2] = (byte) val; out[off + 3] = (byte) 255;
        }
        return out;
    }

    static byte[] solid(int v, int w, int h) {
        byte[] out = new byte[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            int off = i * 4;
            out[off] = (byte) v; out[off + 1] = (byte) v; out[off + 2] = (byte) v; out[off + 3] = (byte) 255;
        }
        return out;
    }

    static int clamp255(int v) { return Math.max(0, Math.min(255, v)); }
    static int clampRange(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
