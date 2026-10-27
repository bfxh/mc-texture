import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 性能审计：装了这个 mod 之后，MC 的资源加载/显存代价到底是多少。
 * 全部为 headless 实测（无 GL 依赖），atlas 尺寸为数学推算（MC 规则：向上取到 2 的幂）。
 */
public class PerfAudit {

    static long t0;

    static long ms() { return System.nanoTime() / 1_000_000; }

    public static void main(String[] args) throws Exception {
        String jar = args.length > 0 ? args[0]
                : "E:/MC/MC/profiles/1.21.4-Fabric/mods/trit-texture-0.1.0.jar";
        String vanillaJar = args.length > 1 ? args[1] : "E:/MC/MC/meta/versions/1.21.4/1.21.4.jar";

        System.out.println("=========== 1. 烘焙资源包（游戏实际加载的）===========");
        List<byte[]> pngs = readPngs(jar);
        List<String> pngNames = readPngNames(jar);
        System.out.printf("PNG 数量: %d%n", pngs.size());

        // 分类：MC 的 block atlas 只加载被 blockstate/model 引用的纹理。
        // labPBR 的 _normal/_rough/_metal/_ao 在纯 vanilla 下无人引用 -> 不进图集、不占显存。
        List<byte[]> loaded = new ArrayList<>();
        List<byte[]> dormant = new ArrayList<>();
        for (int i = 0; i < pngs.size(); i++) {
            String base = pngNames.get(i).substring(0, pngNames.get(i).length() - 4);
            boolean pbr = base.endsWith("_normal") || base.endsWith("_rough")
                    || base.endsWith("_metal") || base.endsWith("_ao");
            (pbr ? dormant : loaded).add(pngs.get(i));
        }
        System.out.printf("  其中被 vanilla 加载(albedo/water/lava): %d 张%n", loaded.size());
        System.out.printf("  纯 vanilla 下休眠(PBR 层, 需 Iris+labPBR 才加载): %d 张%n", dormant.size());

        // --- 解码耗时（多次取中位数，避免 JIT 预热污染）---
        double[] decTimes = new double[5];
        long rawBytes = 0;
        int wMax = 0, hMax = 0;
        for (int r = 0; r < 5; r++) {
            long s = System.nanoTime();
            long bytes = 0;
            for (byte[] p : pngs) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(p));
                int w = img.getWidth(), h = img.getHeight();
                wMax = Math.max(wMax, w); hMax = Math.max(hMax, h);
                int[] rgba = new int[w * h];
                img.getRGB(0, 0, w, h, rgba, 0, w);
                bytes += (long) w * h * 4;
                if (rgba[0] == 0x7F7F7F7F) throw new AssertionError(); // 防 JIT 优化掉
            }
            rawBytes = bytes;
            decTimes[r] = (System.nanoTime() - s) / 1e6;
        }
        Arrays.sort(decTimes);
        System.out.printf("单张尺寸: %d x %d%n", wMax, hMax);
        System.out.printf("解码后 RGBA 总量: %.2f MB%n", rawBytes / 1048576.0);
        System.out.printf("PNG 解码耗时(中位数): %.0f ms%n", decTimes[2]);

        // --- mip 链生成耗时 ---
        double[] mipTimes = new double[5];
        long mipBytes = 0;
        for (int r = 0; r < 5; r++) {
            long s = System.nanoTime();
            long mb = 0;
            for (byte[] p : pngs) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(p));
                int w = img.getWidth(), h = img.getHeight();
                int[] cur = new int[w * h];
                img.getRGB(0, 0, w, h, cur, 0, w);
                int cw = w, ch = h;
                while (cw > 1 || ch > 1) {
                    int nw = Math.max(1, cw >> 1), nh = Math.max(1, ch >> 1);
                    int[] nx = new int[nw * nh];
                    for (int y = 0; y < nh; y++) {
                        for (int x = 0; x < nw; x++) {
                            int x0 = x * 2, y0 = y * 2;
                            int x1 = Math.min(x0 + 1, cw - 1), y1 = Math.min(y0 + 1, ch - 1);
                            int a = cur[y0 * cw + x0], b = cur[y0 * cw + x1],
                                c = cur[y1 * cw + x0], d = cur[y1 * cw + x1];
                            int r8 = ((a >> 16 & 255) + (b >> 16 & 255) + (c >> 16 & 255) + (d >> 16 & 255)) >> 2;
                            int g8 = ((a >> 8 & 255) + (b >> 8 & 255) + (c >> 8 & 255) + (d >> 8 & 255)) >> 2;
                            int b8 = ((a & 255) + (b & 255) + (c & 255) + (d & 255)) >> 2;
                            nx[y * nw + x] = (255 << 24) | (r8 << 16) | (g8 << 8) | b8;
                        }
                    }
                    mb += (long) nw * nh * 4;
                    cur = nx; cw = nw; ch = nh;
                }
            }
            mipBytes = mb;
            mipTimes[r] = (System.nanoTime() - s) / 1e6;
        }
        Arrays.sort(mipTimes);
        System.out.printf("mip 链额外字节: %.2f MB%n", mipBytes / 1048576.0);
        System.out.printf("解码+mip 总耗时(中位数): %.0f ms%n", (decTimes[2] + mipTimes[2]));

        int nLoaded = loaded.size();
        long totalPx = rawBytes / 4;
        long loadedPx = 0;
        int lAtlas = 0;
        {
            // 只按"会被加载的"算真实 atlas
            long px = 0;
            for (byte[] p : loaded) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(p));
                px += (long) img.getWidth() * img.getHeight();
            }
            loadedPx = px;
            lAtlas = atlasSize(px, wMax, hMax);
        }
        System.out.println();
        System.out.println("=========== 2. 图集(atlas)占用 —— 按真实加载的纹理算 ===========");
        System.out.printf("[纯 vanilla] 加载 %d 张, 总像素 %,d%n", nLoaded, loadedPx);
        System.out.printf("[纯 vanilla] block atlas 边长: %d x %d%n", lAtlas, lAtlas);
        System.out.printf("[纯 vanilla] atlas RGBA8: %.1f MB   含 mip: %.1f MB%n",
                (double) lAtlas * lAtlas * 4 / 1048576,
                (double) lAtlas * lAtlas * 4 * 1.3333 / 1048576);
        int iAtlas = atlasSize(totalPx, wMax, hMax);
        System.out.printf("[Iris+labPBR] 全部 %d 张 -> atlas %d x %d, RGBA8 %.1f MB, 含 mip %.1f MB%n",
                pngs.size(), iAtlas, iAtlas,
                (double) iAtlas * iAtlas * 4 / 1048576,
                (double) iAtlas * iAtlas * 4 * 1.3333 / 1048576);

        System.out.println();
        System.out.println("=========== 3. 对照：原版**完整** block atlas ===========");
        List<byte[]> vanAll = readVanillaAll(vanillaJar);
        System.out.printf("原版 block 纹理总数: %d%n", vanAll.size());
        long vRaw = 0;
        int vw = 0, vh = 0;
        long vPx = 0;
        double[] vT = new double[3];
        for (int r = 0; r < 3; r++) {
            long bytes = 0, px = 0;
            long st = System.nanoTime();
            for (byte[] p : vanAll) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(p));
                vw = Math.max(vw, img.getWidth()); vh = Math.max(vh, img.getHeight());
                int[] rgba = new int[img.getWidth() * img.getHeight()];
                img.getRGB(0, 0, img.getWidth(), img.getHeight(), rgba, 0, img.getWidth());
                bytes += (long) img.getWidth() * img.getHeight() * 4;
                px += (long) img.getWidth() * img.getHeight();
            }
            vRaw = bytes; vPx = px;
            vT[r] = (System.nanoTime() - st) / 1e6;
        }
        Arrays.sort(vT);
        System.out.printf("原版单张最大: %d x %d%n", vw, vh);
        System.out.printf("原版解码后 RGBA: %.2f MB%n", vRaw / 1048576.0);
        System.out.printf("原版解码耗时: %.0f ms%n", vT[1]);
        int vAtlas = atlasSize(vPx, vw, vh);
        System.out.printf("原版 atlas 边长: %d x %d -> %.1f MB (含 mip %.1f MB)%n",
                vAtlas, vAtlas, (double) vAtlas * vAtlas * 4 / 1048576,
                (double) vAtlas * vAtlas * 4 * 1.3333 / 1048576);

        System.out.println();
        System.out.println("=========== 4. 结论：代价倍数（mod vs 原版完整 atlas）===========");
        System.out.printf("启动解码耗时: %.2fx   (mod %d 张大图 vs 原版 %d 张小图)%n",
                decTimes[2] / Math.max(vT[1], 0.01), pngs.size(), vanAll.size());
        System.out.printf("显存(纯 vanilla, 只看参与的图集): %.1fx%n",
                ((double) lAtlas * lAtlas) / ((double) vAtlas * vAtlas));
        System.out.printf("显存(若装 Iris+labPBR, 全部层生效): %.1fx%n",
                ((double) iAtlas * iAtlas) / ((double) vAtlas * vAtlas));
        System.out.printf("解码后数据量: %.1fx%n", (double) rawBytes / vRaw);

        System.out.println();
        System.out.printf("%n=========== 5. 降采样收益：把 512x510 降到 N（按 %d 张被加载的纹理）===========%n", nLoaded);
        System.out.printf("%-10s %-12s %-12s %-12s %-14s%n",
                "分辨率", "atlas边长", "atlas显存MB", "含mip显存MB", "解码耗时ms(估)");
        for (int n : new int[]{512, 256, 128, 64}) {
            double scale = (double) n / 512.0;
            long px = (long) (loadedPx * scale * scale);
            int a = atlasSize(px, n, Math.max(1, (int) (510 * scale)));
            double mem = (double) a * a * 4 / 1048576;
            double dec = decTimes[2] * scale * scale;
            System.out.printf("%-10d %-12d %-12.1f %-12.1f %-14.0f%n",
                    n, a, mem, mem * 1.3333, dec);
        }
    }

    /** MC atlas 规则：能装下所有纹理的最小 2 的幂边长（简化：按面积开方 + 单行排列约束） */
    static int atlasSize(long totalPx, int tw, int th) {
        int perRow = 1;
        // 面积下界
        long need = totalPx;
        int side = 16;
        while ((long) side * side < need) side <<= 1;
        // 单行约束：一行至少能放一张纹理，且行数*行高不超过边长
        while (side / tw * (side / th) < Math.ceil(need / (double) (tw * th))) side <<= 1;
        return side;
    }

    static List<byte[]> readPngs(String jar) throws IOException {
        List<byte[]> out = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(jar))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().contains("/textures/block/") && e.getName().endsWith(".png")) {
                    out.add(zis.readAllBytes());
                }
            }
        }
        return out;
    }

    static List<String> readPngNames(String jar) throws IOException {
        List<String> out = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(jar))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName();
                if (n.contains("/textures/block/") && n.endsWith(".png")) {
                    out.add(n.substring(n.lastIndexOf('/') + 1));
                }
            }
        }
        return out;
    }

    static List<byte[]> readVanillaAll(String jar) throws IOException {
        List<byte[]> out = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(jar))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName();
                if (n.startsWith("assets/minecraft/textures/block/") && n.endsWith(".png")) {
                    out.add(zis.readAllBytes());
                }
            }
        }
        return out;
    }
}
