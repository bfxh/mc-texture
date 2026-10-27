package dev.trit.mc.texture;

import java.util.Random;

/**
 * 自包含端到端验证：own 路线「GPU 直采样」的格式层正确性。
 *
 * 链路：RGBA → TritEncoder.rgbaToTtx2Mipped(TTX2 多 mip) → buildDataTexture（按
 * ttx_sample.glsl 的布局把块摊成 RGBA8UI 数据纹理：每块 3 纹素，跨 mip 连续）→
 * gpuSample（Java 逐位镜像 GLSL 的整数运算）→ 与 TritCodec.decodeRgba 逐像素比对。
 *
 * 关键点：块尺寸锁定 BLOCK_W=4 / BLOCK_H=8（与 TritCodec 当前的 4×8 一致）。
 * 旧的 trit-mc-mod/verify/GpuPath.java 用的是 BLOCK_H=10，属于 4×10 老格式残留，
 * 已在此用 4×8 自包含实现替代，不依赖任何外部 .ttx2 文件，可重复运行。
 *
 * 同时量化显存账：TTX 数据纹理（每块 12 字节）vs RGBA8（每纹素 4 字节）。
 */
public final class TtxGpuVerify {

    static final int BLOCK_W = TritCodec.BLOCK_WIDTH;    // 4
    static final int BLOCK_H = TritCodec.BLOCK_HEIGHT;   // 8
    static final int DATA_WIDTH = 384;                   // 每行纹素数；必须 3 的倍数，保证块不跨行

    static byte[] dataTex;
    static int[] levelOffset = new int[8];
    static int[] levelCols = new int[8];
    static int[] levelRows = new int[8];
    static int levelCount;

    public static void main(String[] args) {
        int seed = 0x1234;
        int files = 0, totalChecked = 0, totalMismatch = 0;
        long rgbaBytes = 0, gpuBytes = 0;

        int[][] sizes = { {64, 64}, {128, 64}, {32, 32}, {256, 256}, {64, 128} };
        for (int[] sz : sizes) {
            int w = sz[0], h = sz[1];
            byte[] rgba = makeRandom(seed++, w, h);
            byte[] ttx2 = TritEncoder.rgbaToTtx2Mipped(rgba, w, h);
            TritCodec.Ttx2Document doc = TritCodec.fromTtx2(ttx2);
            buildDataTexture(doc);

            int bad = 0, checked = 0;
            for (int l = 0; l < doc.levels.length; l++) {
                TritCodec.TritTexture t = doc.levels[l];
                byte[] ref = TritCodec.decodeRgba(t);
                for (int y = 0; y < t.height; y++)
                    for (int x = 0; x < t.width; x++) {
                        int[] got = gpuSample(x, y, l);
                        int off = (y * t.width + x) * 4;
                        checked++;
                        if (got[0] != (ref[off] & 0xff) || got[1] != (ref[off + 1] & 0xff)
                                || got[2] != (ref[off + 2] & 0xff)) bad++;
                    }
                rgbaBytes += (long) t.width * t.height * 4;
                gpuBytes += (long) t.blocks.length * 12;   // 每块 3 纹素 × 4 字节
            }
            files++;
            totalChecked += checked;
            totalMismatch += bad;
            System.out.printf("  %dx%d  mips=%d  checked=%d  mismatch=%d%n",
                    w, h, doc.levels.length, checked, bad);
        }

        System.out.println();
        System.out.printf("文件=%d  采样点=%d  mismatch=%d%n", files, totalChecked, totalMismatch);
        System.out.printf("[vram] RGBA8            = %,d B (%.2f MB)%n", rgbaBytes, rgbaBytes / 1048576.0);
        System.out.printf("[vram] TTX GPU数据纹理  = %,d B (%.2f MB)%n", gpuBytes, gpuBytes / 1048576.0);
        System.out.printf("[vram] 显存收益         = %.2f×%n", (double) rgbaBytes / gpuBytes);

        boolean ok = totalMismatch == 0;
        System.out.println(ok
                ? "==== GPU 解码路径验证通过：GLSL 逻辑 == CPU 解码器（4×8）===="
                : "==== 验证失败 ====");
        if (!ok) System.exit(1);
    }

    static byte[] makeRandom(int seed, int w, int h) {
        Random r = new Random(seed);
        byte[] b = new byte[w * h * 4];
        for (int i = 0; i < b.length; i++) b[i] = (byte) r.nextInt(256);
        return b;
    }

    /** 镜像 mod 侧上传逻辑 / ttx_sample.glsl 的布局假设。 */
    static void buildDataTexture(TritCodec.Ttx2Document doc) {
        levelCount = doc.levels.length;
        if (levelCount > 8) throw new IllegalStateException("mip 级数 " + levelCount + " 超出 uniform 数组容量 8");
        int totalBlocks = 0;
        for (TritCodec.TritTexture t : doc.levels) totalBlocks += t.blocks.length;
        int totalTexels = totalBlocks * 3;
        dataTex = new byte[totalTexels * 4];

        int gi = 0;
        for (int l = 0; l < levelCount; l++) {
            TritCodec.TritTexture t = doc.levels[l];
            levelOffset[l] = gi;
            levelCols[l] = t.width / BLOCK_W;
            levelRows[l] = t.height / BLOCK_H;
            for (byte[] blk : t.blocks) {
                // 每块 3 纹素（共 12 字节纹素槽，但 Java 块是 11 字节：4 端点 + 7 trit）。
                // texel0 = 端点[e0hi,e0lo,e1hi,e1lo]；texel1 = payload[0..3]；
                // texel2 = payload[4..6]，第 4 字节保持 0（padding）——GLSL 采样逻辑
                // by∈[0,6] 只访问 payload[4..6]，绝不读 texel2.a。
                int base = (gi * 3) * 4;
                System.arraycopy(blk, 0, dataTex, base, 4);
                System.arraycopy(blk, 4, dataTex, base + 4, 4);
                System.arraycopy(blk, 8, dataTex, base + 8, 3);
                gi++;
            }
        }

        if (DATA_WIDTH % 3 != 0)
            throw new IllegalStateException("ttxDataWidth 必须是 3 的倍数，否则块会跨行");
        for (int g = 0; g < totalBlocks; g++) {
            int t = g * 3;
            if (t / DATA_WIDTH != (t + 2) / DATA_WIDTH)
                throw new IllegalStateException("块 #" + g + " 的 3 个纹素跨行，线性排布前提被破坏");
        }
    }

    /** 镜像 GLSL：texelFetch(ttxData, ivec2(t % ttxDataWidth, t / ttxDataWidth), 0).c */
    static int texelFetch(int t, int comp) {
        int row = t / DATA_WIDTH, col = t - row * DATA_WIDTH;
        return dataTex[(row * DATA_WIDTH + col) * 4 + comp] & 0xff;
    }

    static int pow3(int n) { return (n <= 0) ? 1 : (n == 1 ? 3 : (n == 2 ? 9 : (n == 3 ? 27 : 81))); }

    static int[] unpack565(int v) {
        int r5 = (v >> 11) & 31, g6 = (v >> 5) & 63, b5 = v & 31;
        return new int[] { (r5 << 3) | (r5 >> 2), (g6 << 2) | (g6 >> 4), (b5 << 3) | (b5 >> 2) };
    }

    /** 镜像 GLSL ttxSample(uv=像素中心, lod)。返回 [r,g,b]（线性空间 /255）。 */
    static int[] gpuSample(int px, int py, int lod) {
        int bc = levelCols[lod], br = levelRows[lod];
        int bx = px / BLOCK_W, by = py / BLOCK_H;
        int ox = px - bx * BLOCK_W, oy = py - by * BLOCK_H;
        int ti = oy * BLOCK_W + ox;

        int gi = levelOffset[lod] + by * bc + bx;
        int t0 = gi * 3, t1 = gi * 3 + 1, t2 = gi * 3 + 2;

        int e0 = (texelFetch(t0, 0) << 8) | texelFetch(t0, 1);
        int e1 = (texelFetch(t0, 2) << 8) | texelFetch(t0, 3);

        int byIdx = ti / 5, slot = ti - byIdx * 5;
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

        int[] c0 = unpack565(e0), c1 = unpack565(e1);
        int[] mid = { (c0[0] + c1[0]) / 2, (c0[1] + c1[1]) / 2, (c0[2] + c1[2]) / 2 };
        return (digit == 0) ? c0 : (digit == 1 ? mid : c1);
    }
}
