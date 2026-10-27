package dev.trit.mc.texture;

import java.io.File;
import java.nio.file.Files;

/**
 * 程序化生成真实感方块 albedo（替代最初的灰度斜条纹占位图），覆盖到 opt/ttx。
 *
 * 每种方块用多倍频值噪声(fBm) + 专属调色板/模式生成 512x512 真实感纹理：
 *   stone 灰岩带裂纹 / dirt 棕土带暗斑 / grass 绿草带亮草点 / oak_log 垂直树皮
 *   coal_ore 石底嵌煤斑 / water 蓝绿波纹 / lava 橙红亮斑带暗裂
 *
 * 同时：写 albedo PNG、编码 TTX2（覆盖占位）、写新 roundtrip 基线、算编码->解码往返 PSNR。
 * 经此一步后，PBR 派生与 resourcepack 生成即基于真实材质，不再依赖占位数据。
 */
public final class GenAlbedo {
    static final int W = 512, H = 512;
    static final String[] NAMES = {"stone", "dirt", "grass_block_top", "oak_log", "coal_ore", "water", "lava"};

    public static void main(String[] a) throws Exception {
        String dir = a.length > 0 ? a[0] : "D:/KF/.yanagent/mc-texture/pack/opt/ttx";
        new File(dir).mkdirs();
        for (String name : NAMES) {
            byte[] rgba = new byte[W * H * 4];
            for (int y = 0; y < H; y++)
                for (int x = 0; x < W; x++) {
                    int[] c = color(name, x, y);
                    int off = (y * W + x) * 4;
                    rgba[off] = (byte) c[0]; rgba[off + 1] = (byte) c[1]; rgba[off + 2] = (byte) c[2]; rgba[off + 3] = (byte) 255;
                }
            PngIO.Rgba img = new PngIO.Rgba(W, H, rgba);
            PngIO.write(img, dir + "/" + name + "_512x512.png");
            byte[] ttx2 = TritEncoder.rgbaToTtx2Mipped(rgba, W, H);
            Files.write(new File(dir, name + ".ttx2").toPath(), ttx2);
            // 新基线 roundtrip（Java 解码回写）
            TtxTextureProvider.Rgba dec = TtxTextureProvider.decode(ttx2);
            PngIO.write(new PngIO.Rgba(dec.width, dec.height, dec.rgba), dir + "/" + name + "_roundtrip.png");
            double psnr = PngIO.psnrRgb(rgba, dec.rgba);
            int before = W * H * 4;
            System.out.printf("%-16s %dx%d  albedo原=%6d B  TTX2=%6d B (%.2f bpp)  往返PSNR=%.1f dB%n",
                name, W, H, before, ttx2.length, (double) ttx2.length * 8 / (W * H), psnr);
        }
        System.out.println("[gen-albedo] DONE");
    }

    static int[] color(String name, int x, int y) {
        switch (name) {
            case "stone": return stone(x, y);
            case "dirt": return dirt(x, y);
            case "grass_block_top": return grass(x, y);
            case "oak_log": return oak(x, y);
            case "coal_ore": return coal(x, y);
            case "water": return water(x, y);
            case "lava": return lava(x, y);
        }
        return new int[]{128, 128, 128};
    }

    // ---- 值噪声 fBm ----
    static int hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        h = h ^ (h >>> 16);
        return h & 0x7fffffff;
    }
    static double rnd(int x, int y, int seed) { return (hash(x, y, seed) & 0xffff) / 65535.0; }
    static double vnoise(double x, double y, int seed) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        double xf = x - xi, yf = y - yi;
        double u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf);
        double a = rnd(xi, yi, seed), b = rnd(xi + 1, yi, seed);
        double c = rnd(xi, yi + 1, seed), d = rnd(xi + 1, yi + 1, seed);
        return a * (1 - u) * (1 - v) + b * u * (1 - v) + c * (1 - u) * v + d * u * v;
    }
    static double fbm(double x, double y, int seed) {
        double s = 0, amp = 0.5, freq = 1;
        for (int o = 0; o < 5; o++) { s += amp * vnoise(x * freq, y * freq, seed + o * 17); freq *= 2; amp *= 0.5; }
        return s;
    }
    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    static int[] stone(int x, int y) {
        double n = fbm(x / 26.0, y / 26.0, 1);
        int g = clamp((int) (135 + (n - 0.5) * 70));
        int r = g - 6, b = g + 10;
        if (fbm(x / 7.0, y / 7.0, 53) > 0.80) { r = (int) (r * 0.5); g = (int) (g * 0.5); b = (int) (b * 0.5); }
        return new int[]{clamp(r), clamp(g), clamp(b)};
    }
    static int[] dirt(int x, int y) {
        double n = fbm(x / 20.0, y / 20.0, 2);
        int r = clamp((int) (125 + (n - 0.5) * 55));
        int g = (int) (r * 0.72), b = (int) (r * 0.5);
        if (fbm(x / 9.0, y / 9.0, 71) > 0.82) { r = (int) (r * 0.6); g = (int) (g * 0.6); b = (int) (b * 0.6); }
        return new int[]{clamp(r), clamp(g), clamp(b)};
    }
    static int[] grass(int x, int y) {
        double n = fbm(x / 18.0, y / 18.0, 3);
        int g = clamp((int) (120 + (n - 0.5) * 75));
        int r = (int) (g * 0.55), b = (int) (g * 0.40);
        if (fbm(x / 4.0, y / 4.0, 88) > 0.70) { g = clamp(g + 25); r = clamp(r + 12); }
        return new int[]{clamp(r), clamp(g), clamp(b)};
    }
    static int[] oak(int x, int y) {
        double bark = vnoise(x / 3.0, y / 40.0, 4);
        double n = fbm(x / 10.0, y / 10.0, 5);
        int r = clamp((int) (110 + (bark - 0.5) * 60 + (n - 0.5) * 30));
        int g = (int) (r * 0.75), b = (int) (r * 0.5);
        return new int[]{clamp(r), clamp(g), clamp(b)};
    }
    static int[] coal(int x, int y) {
        double n = fbm(x / 26.0, y / 26.0, 1);
        int g = clamp((int) (135 + (n - 0.5) * 60));
        int r = g - 6, b = g + 10;
        double ore = fbm(x / 14.0, y / 14.0, 7);
        if (ore > 0.62) { int c = clamp((int) (25 + (ore - 0.62) * 200)); r = c; g = c; b = c; }
        return new int[]{clamp(r), clamp(g), clamp(b)};
    }
    static int[] water(int x, int y) {
        double n = fbm(x / 22.0, y / 22.0, 8);
        int r = clamp((int) (40 + (n - 0.5) * 30));
        int g = clamp((int) (90 + (n - 0.5) * 40));
        int b = clamp((int) (160 + (n - 0.5) * 50));
        double wv = Math.sin(y * 0.06 + n * 6.0);
        g = clamp(g + (int) (wv * 15));
        return new int[]{clamp(r), clamp(g), clamp(b)};
    }
    static int[] lava(int x, int y) {
        double n = fbm(x / 20.0, y / 20.0, 9);
        int r = clamp((int) (200 + (n - 0.5) * 50));
        int g = clamp((int) (80 + (n - 0.5) * 50));
        int b = clamp((int) (20 + (n - 0.5) * 30));
        double hot = fbm(x / 10.0, y / 10.0, 11);
        if (hot > 0.60) { r = 255; g = clamp(g + (int) ((hot - 0.6) * 300)); b = clamp(b + (int) ((hot - 0.6) * 120)); }
        if (fbm(x / 8.0, y / 8.0, 13) < 0.30) { r = (int) (r * 0.6); g = (int) (g * 0.6); b = (int) (b * 0.6); }
        return new int[]{clamp(r), clamp(g), clamp(b)};
    }
}
