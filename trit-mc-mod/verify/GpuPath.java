import dev.trit.mc.texture.*;

import java.io.File;
import java.nio.file.*;
import java.util.*;

/**
 * GPU 路径端到端验证（纯 headless，不需要 GL 上下文）。
 *
 * 做三件事：
 *  1) 把 ttx 目录里只有 1 级 mip 的 TTX2 重编为完整 mip 链（根因：生成器走单级便捷函数）。
 *  2) 按 gpu/ttx_sample.glsl 的布局，把块数据摊成一张 RGBA8UI「数据纹理」
 *     （每块 3 个纹素，按全局块索引线性排布，跨 mip 级连续）。
 *  3) 用 Java 逐位镜像 GLSL 的整数运算（同款取模/整除/移位/RGB565 位复制），
 *     对全部文件的全部 mip 级逐像素采样，与官方解码器 TritCodec 比对。
 *
 * 3) 全绿 == GLSL 着色器逻辑与 CPU 解码器在数值上完全等价。
 */
public final class GpuPath {

    // ---- GLSL 常量（与 ttx_sample.glsl 一致）----
    static final int BLOCK_W = 4;
    static final int BLOCK_H = 10;
    static final int DATA_WIDTH = 384;          // 每行纹素数 = 128 块 * 3

    // ---- 数据纹理：每纹素 4 字节（RGBA8UI）----
    static byte[] dataTex;                       // dataTex[t*4 + c]
    static int dataTexels;
    static int[] levelOffset = new int[8];
    static int[] levelCols = new int[8];
    static int[] levelRows = new int[8];
    static int levelCount;

    public static void main(String[] args) throws Exception {
        String ttxDir = args.length > 0 ? args[0]
            : "D:/KF/.yanagent/mc-texture/trit-mc-mod/fabric/resources/ttx";
        String outDir = args.length > 1 ? args[1]
            : "D:/KF/.yanagent/mc-texture/trit-mc-mod/verify/ttx_mip";
        new File(outDir).mkdirs();

        File[] files = new File(ttxDir).listFiles((d, n) -> n.endsWith(".ttx2"));
        Arrays.sort(files);
        System.out.println("[regen] 源目录 " + ttxDir + "  文件数=" + files.length);

        // ---------- 1) 重编为完整 mip 链 ----------
        int minLv = 99, maxLv = 0, regen = 0;
        Map<String, byte[]> mipped = new LinkedHashMap<>();
        for (File f : files) {
            byte[] old = Files.readAllBytes(f.toPath());
            TritCodec.Ttx2Document od = TritCodec.fromTtx2(old);

            // 最细级必须原样保留：重编码不幂等（RGB565 向下取整会让
            // min(解码结果) < min(原始像素)，端点漂移 -> 索引全变）。
            TritCodec.TritTexture l0 = od.levels[0];
            byte[] oldL0 = TritCodec.decodeRgba(l0);

            byte[] neu;
            if (od.levels.length > 1) {
                neu = old;                       // 已是多级，原样保留
            } else {
                List<TtxTextureProvider.Rgba> chain = TtxTextureProvider.buildMipChain(
                    new TtxTextureProvider.Rgba(l0.width, l0.height, oldL0));
                TritCodec.TritTexture[] levels = new TritCodec.TritTexture[chain.size()];
                levels[0] = l0;                  // 最细级沿用原块字节
                for (int i = 1; i < chain.size(); i++) {
                    TtxTextureProvider.Rgba r = chain.get(i);
                    levels[i] = TritEncoder.encodeRgba(r.rgba, r.width, r.height);
                }
                neu = TritEncoder.toTtx2Tex(levels);
            }

            TritCodec.Ttx2Document nd = TritCodec.fromTtx2(neu);
            byte[] newL0 = TritCodec.decodeRgba(nd.levels[0]);

            if (!Arrays.equals(oldL0, newL0)) throw new AssertionError(f.getName() + " 重编后最细级变了！");
            // 已是多级时原样保留，不做级数断言（保证工具可重复运行）
            if (od.levels.length == 1 && nd.levels.length <= 1)
                throw new AssertionError(f.getName() + " mip 级数未增加");
            regen++;
            minLv = Math.min(minLv, nd.levels.length);
            maxLv = Math.max(maxLv, nd.levels.length);
            mipped.put(f.getName(), neu);
            Files.write(new File(outDir, f.getName()).toPath(), neu);
        }
        System.out.printf("[regen] 重编=%d  每文件 mip 级数=%d..%d  尺寸序列=%s%n",
            regen, minLv, maxLv, TritEncoder.levelDims(mipped.values().iterator().next()));

        // ---------- 2+3) 摊成数据纹理 + 逐像素比对 ----------
        int totalIdentical = 0, totalChecked = 0, totalMismatch = 0;
        long gpuBytes = 0, rgbaBytes = 0;

        for (Map.Entry<String, byte[]> e : mipped.entrySet()) {
            TritCodec.Ttx2Document doc = TritCodec.fromTtx2(e.getValue());
            buildDataTexture(doc);

            int bad = 0, checked = 0;
            for (int l = 0; l < doc.levels.length; l++) {
                TritCodec.TritTexture t = doc.levels[l];
                byte[] ref = TritCodec.decodeRgba(t);          // 官方 CPU 解码
                for (int y = 0; y < t.height; y++) {
                    for (int x = 0; x < t.width; x++) {
                        int[] got = gpuSample(x, y, l);        // 镜像 GLSL 的采样
                        int off = (y * t.width + x) * 4;
                        checked++;
                        if (got[0] != (ref[off] & 0xff) || got[1] != (ref[off + 1] & 0xff)
                                || got[2] != (ref[off + 2] & 0xff)) bad++;
                    }
                }
            }
            totalChecked += checked;
            totalMismatch += bad;
            if (bad == 0) totalIdentical++;
            if (bad > 0) System.out.println("  !! " + e.getKey() + " mismatch=" + bad);
        }

        // ---------- 显存账 ----------
        File first = new File(outDir, files[0].getName());
        TritCodec.Ttx2Document d0 = TritCodec.fromTtx2(Files.readAllBytes(first.toPath()));
        long blocksAll = 0;
        for (TritCodec.TritTexture t : d0.levels) blocksAll += t.blocks.length;
        gpuBytes = blocksAll * 12;
        for (TritCodec.TritTexture t : d0.levels) rgbaBytes += (long) t.width * t.height * 4;

        System.out.println();
        System.out.println("[gpu] 数据纹理布局：宽=" + DATA_WIDTH + " 纹素，每块 3 纹素，跨 mip 连续");
        System.out.println("[gpu] 逐像素比对：文件=" + mipped.size()
            + "  与官方解码一致=" + totalIdentical + "/" + mipped.size()
            + "  采样点=" + totalChecked + "  mismatch=" + totalMismatch);
        System.out.println();
        System.out.printf("[vram] 单张 %dx%d（含 %d 级 mip）%n",
            d0.levels[0].width, d0.levels[0].height, d0.levels.length);
        System.out.printf("[vram]   RGBA8   = %,10d B  (%.2f MB)%n", rgbaBytes, rgbaBytes / 1048576.0);
        System.out.printf("[vram]   TTX GPU = %,10d B  (%.2f MB)%n", gpuBytes, gpuBytes / 1048576.0);
        System.out.printf("[vram]   显存收益 = %.2f×%n", (double) rgbaBytes / gpuBytes);

        boolean ok = totalMismatch == 0 && totalIdentical == mipped.size();
        System.out.println();
        System.out.println(ok ? "==== GPU 解码路径验证通过：GLSL 逻辑 == CPU 解码器 ===="
                              : "==== GPU 解码路径验证失败 ====");
        if (!ok) System.exit(1);
    }

    // =====================================================================
    // 数据纹理构建（镜像 mod 侧上传逻辑 / ttx_sample.glsl 的布局假设）
    // =====================================================================
    static void buildDataTexture(TritCodec.Ttx2Document doc) {
        levelCount = doc.levels.length;
        if (levelCount > 8) throw new IllegalStateException("mip 级数 " + levelCount + " 超出 uniform 数组容量 8");
        int totalBlocks = 0;
        for (TritCodec.TritTexture t : doc.levels) totalBlocks += t.blocks.length;
        dataTexels = totalBlocks * 3;
        dataTex = new byte[dataTexels * 4];

        int gi = 0;
        for (int l = 0; l < levelCount; l++) {
            TritCodec.TritTexture t = doc.levels[l];
            levelOffset[l] = gi;
            levelCols[l] = t.width / BLOCK_W;
            levelRows[l] = t.height / BLOCK_H;
            for (byte[] blk : t.blocks) {
                for (int k = 0; k < 3; k++) {
                    int dst = (gi * 3 + k) * 4;
                    System.arraycopy(blk, k * 4, dataTex, dst, 4);
                }
                gi++;
            }
        }

        // ---- 断言 GLSL 布局的两条前提（否则着色器会静默读到错误块）----
        if (DATA_WIDTH % 3 != 0)
            throw new IllegalStateException("ttxDataWidth 必须是 3 的倍数，否则块会跨行");
        for (int g = 0; g < totalBlocks; g++) {
            int t = g * 3;
            if (t / DATA_WIDTH != (t + 2) / DATA_WIDTH)
                throw new IllegalStateException("块 #" + g + " 的 3 个纹素跨行，线性排布前提被破坏");
        }
    }

    // =====================================================================
    // GLSL 逻辑的 Java 逐位镜像
    //   所有除法/取模均为整数语义；uint 用 int 承载（值域 ≤ 255，无符号不影响）
    // =====================================================================
    /** 镜像 GLSL：texelFetch(ttxData, ivec2(t % ttxDataWidth, t / ttxDataWidth), 0).c */
    static int texelFetch(int t, int comp) {
        int row = t / DATA_WIDTH, col = t - row * DATA_WIDTH;
        return dataTex[(row * DATA_WIDTH + col) * 4 + comp] & 0xff;
    }

    static int[] gpuSample(int px, int py, int lod) {
        // ivec2 bs = ttxLevelBlocks[lod]
        int bc = levelCols[lod], br = levelRows[lod];

        // ivec2 b = p / block ; ivec2 o = p - b*block
        int bx = px / BLOCK_W, by = py / BLOCK_H;
        int ox = px - bx * BLOCK_W, oy = py - by * BLOCK_H;
        int ti = oy * BLOCK_W + ox;

        int gi = levelOffset[lod] + by * bc + bx;
        int t0 = gi * 3, t1 = gi * 3 + 1, t2 = gi * 3 + 2;

        int e0 = (texelFetch(t0, 0) << 8) | texelFetch(t0, 1);
        int e1 = (texelFetch(t0, 2) << 8) | texelFetch(t0, 3);

        int byIdx = ti / 5;
        int slot = ti - byIdx * 5;
        int vb;
        if (byIdx < 4) {
            vb = (byIdx == 0) ? texelFetch(t1, 0)
               : (byIdx == 1) ? texelFetch(t1, 1)
               : (byIdx == 2) ? texelFetch(t1, 2) : texelFetch(t1, 3);
        } else {
            int k = byIdx - 4;
            vb = (k == 0) ? texelFetch(t2, 0)
               : (k == 1) ? texelFetch(t2, 1)
               : (k == 2) ? texelFetch(t2, 2) : texelFetch(t2, 3);
        }
        int digit = (vb / pow3(slot)) % 3;

        int[] c0 = unpack565(e0);
        int[] c1 = unpack565(e1);
        int[] mid = {(c0[0] + c1[0]) / 2, (c0[1] + c1[1]) / 2, (c0[2] + c1[2]) / 2};
        return (digit == 0) ? c0 : (digit == 1 ? mid : c1);
    }

    static int pow3(int n) {
        return (n <= 0) ? 1 : (n == 1 ? 3 : (n == 2 ? 9 : (n == 3 ? 27 : 81)));
    }

    static int[] unpack565(int v) {
        int r5 = (v >> 11) & 31, g6 = (v >> 5) & 63, b5 = v & 31;
        return new int[]{(r5 << 3) | (r5 >> 2), (g6 << 2) | (g6 >> 4), (b5 << 3) | (b5 >> 2)};
    }
}
