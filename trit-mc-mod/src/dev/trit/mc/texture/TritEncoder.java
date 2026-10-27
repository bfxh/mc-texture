package dev.trit.mc.texture;

import java.util.Arrays;
import java.util.List;

/**
 * 忠实移植 trit-texture 的 TT3 编码 + TTX1/TTX2 容器**写入**侧。
 *
 * 逐字节对齐 Rust crate（D:/KF/trit/crates/trit-texture/src/lib.rs, ttx.rs, ttx2/mod.rs）：
 *  - block_endpoints：逐通道 min/max → RGB565（e0=min, e1=max）
 *  - palette_from_endpoints：{min, mid, max}，mid = (a+b)/2 逐通道
 *  - nearest_index：平方 RGB 距离，并列取较小下标（严格小于才换）
 *  - pack_trits：5 trit/字节，低位 trit 在前，Σ digit_i · 3^i
 *  - TTX1：magic + w/h/blocks(LE) + 块 + GF(3) CRC-3（生成子 x^3+2x+1 互反式，低次在前）
 *  - TTX2：magic + kind + flags + reserved + section_len(LE) + section + GF(3) CRC-16
 *
 * 仅编码侧；解码侧见 TritCodec（已 7/7 逐字节验证 == Rust）。
 */
public final class TritEncoder {

    static final int[] TTX1_GEN = {1, 0, 2, 1};                // 低次在前
    static final int[] TTX2_GEN16 = {2, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1};

    private TritEncoder() {}

    // ----- RGB565 / trit 工具（与 TritCodec 同义，独立副本避免改解码器可见性） -----
    static int pow3(int n) { int r = 1; for (int k = 0; k < n; k++) r *= 3; return r; }
    static int mod3(int x) { return ((x % 3) + 3) % 3; }

    static int packRgb565(int r, int g, int b) {
        return ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
    }

    static int[] blockTrits(byte[] block) {
        int[] out = new int[TritCodec.TRITS_PER_BLOCK];
        for (int i = 0; i < TritCodec.TRITS_PER_BLOCK; i++) {
            int b = block[4 + i / 5] & 0xff;
            int slot = i % 5;
            out[i] = (b / pow3(slot)) % 3;
        }
        return out;
    }

    static int[] gf3Rem(int[] digits, int[] poly) {
        int degree = poly.length - 1;
        int[] buf = new int[digits.length + degree];
        System.arraycopy(digits, 0, buf, 0, digits.length);
        for (int i = 0; i <= buf.length - poly.length; i++) {
            int coeff = buf[i];
            if (coeff != 0) {
                for (int j = 0; j < poly.length; j++)
                    buf[i + j] = mod3(buf[i + j] - coeff * poly[j]);
            }
        }
        return Arrays.copyOfRange(buf, buf.length - degree, buf.length);
    }

    // ----- 编码核心（镜像 lib.rs encode_rgba / encode_block） -----

    /** RGBA → TritTexture（TT3 有损压缩：3 级调色板/块）。 */
    public static TritCodec.TritTexture encodeRgba(byte[] rgba, int width, int height) {
        if (width % TritCodec.BLOCK_WIDTH != 0 || height % TritCodec.BLOCK_HEIGHT != 0)
            throw new IllegalArgumentException("dims not multiple of block grid");
        if (rgba.length != width * height * 4)
            throw new IllegalArgumentException("RGBA length mismatch");
        int cols = width / TritCodec.BLOCK_WIDTH;
        int rows = height / TritCodec.BLOCK_HEIGHT;
        byte[][] blocks = new byte[cols * rows][];
        int bi = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                blocks[bi++] = encodeBlock(rgba, width, height, col, row);
            }
        }
        return new TritCodec.TritTexture(width, height, blocks);
    }

    static byte[] encodeBlock(byte[] rgba, int width, int height, int blockCol, int blockRow) {
        int ox = blockCol * TritCodec.BLOCK_WIDTH;
        int oy = blockRow * TritCodec.BLOCK_HEIGHT;
        // 1) 端点：逐通道 min/max
        int[] min = {255, 255, 255};
        int[] max = {0, 0, 0};
        for (int i = 0; i < TritCodec.BLOCK_PIXELS; i++) {
            int x = ox + i % TritCodec.BLOCK_WIDTH;
            int y = oy + i / TritCodec.BLOCK_WIDTH;
            int off = (y * width + x) * 4;
            for (int c = 0; c < 3; c++) {
                int v = rgba[off + c] & 0xff;
                if (v < min[c]) min[c] = v;
                if (v > max[c]) max[c] = v;
            }
        }
        int e0 = packRgb565(min[0], min[1], min[2]);
        int e1 = packRgb565(max[0], max[1], max[2]);
        // 2) 调色板 {min, mid, max}
        int[] a = unpack(e0), b = unpack(e1);
        int[][] pal = new int[3][];
        pal[0] = a;
        pal[1] = new int[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2};
        pal[2] = b;
        // 3) 每纹素最近调色板色 → trit 索引（0/1/2）
        int[] digits = new int[TritCodec.TRITS_PER_BLOCK];
        for (int i = 0; i < TritCodec.BLOCK_PIXELS; i++) {
            int x = ox + i % TritCodec.BLOCK_WIDTH;
            int y = oy + i / TritCodec.BLOCK_WIDTH;
            int off = (y * width + x) * 4;
            digits[i] = nearestIndex(pal, rgba[off] & 0xff, rgba[off + 1] & 0xff, rgba[off + 2] & 0xff);
        }
        // 4) 打包块
        byte[] block = new byte[TritCodec.TRIT_BLOCK_BYTES];
        block[0] = (byte) (e0 >> 8);
        block[1] = (byte) e0;
        block[2] = (byte) (e1 >> 8);
        block[3] = (byte) e1;
        for (int i = 0; i < TritCodec.TRITS_PER_BLOCK; i++) {
            int by = i / 5, slot = i % 5;
            block[4 + by] += (byte) (digits[i] * pow3(slot));
        }
        return block;
    }

    static int[] unpack(int v) {
        int r5 = (v >> 11) & 0x1f, g6 = (v >> 5) & 0x3f, b5 = v & 0x1f;
        return new int[]{(r5 << 3) | (r5 >> 2), (g6 << 2) | (g6 >> 4), (b5 << 3) | (b5 >> 2)};
    }

    static int nearestIndex(int[][] pal, int r, int g, int b) {
        int best = 0, bestDist = Integer.MAX_VALUE;
        for (int idx = 0; idx < 3; idx++) {
            int dr = r - pal[idx][0], dg = g - pal[idx][1], db = b - pal[idx][2];
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) { best = idx; bestDist = dist; }
        }
        return best;
    }

    // ----- 容器写入（镜像 ttx.rs / ttx2/mod.rs 写侧） -----

    /** TritTexture → TTX1 容器。 */
    public static byte[] toTtx1(TritCodec.TritTexture tex) {
        int blocks = tex.blocks.length;
        byte[] out = new byte[16 + blocks * TritCodec.TRIT_BLOCK_BYTES + 1];
        out[0] = 'T'; out[1] = 'T'; out[2] = 'X'; out[3] = '1';
        putU32LE(out, 4, tex.width);
        putU32LE(out, 8, tex.height);
        putU32LE(out, 12, blocks);
        int p = 16;
        for (byte[] blk : tex.blocks) { System.arraycopy(blk, 0, out, p, blk.length); p += blk.length; }
        int[] trits = concatBlockTrits(tex.blocks);
        int[] rem = gf3Rem(trits, TTX1_GEN);
        out[p] = (byte) (rem[0] * 9 + rem[1] * 3 + rem[2]);
        return out;
    }

    /** 一级或多级 TritTexture → TTX2 容器（kind=TEX）。 */
    public static byte[] toTtx2Tex(TritCodec.TritTexture... levels) {
        // section = u32 level_count + 每级(u32 w, u32 h, 块)
        int sectionLen = 4;
        for (TritCodec.TritTexture t : levels) sectionLen += 8 + t.blocks.length * TritCodec.TRIT_BLOCK_BYTES;
        byte[] section = new byte[sectionLen];
        putU32LE(section, 0, levels.length);
        int c = 4;
        for (TritCodec.TritTexture t : levels) {
            putU32LE(section, c, t.width); c += 4;
            putU32LE(section, c, t.height); c += 4;
            for (byte[] blk : t.blocks) { System.arraycopy(blk, 0, section, c, blk.length); c += blk.length; }
        }
        // 拼接全部块 trit 算 CRC-16
        int[] trits = concatAllBlockTrits(levels);
        int[] rem = gf3Rem(trits, TTX2_GEN16);
        long value = 0;
        for (int i = 0; i < rem.length; i++) value += (long) rem[i] * pow3(i);
        // 头部 12 + section + 4 字节校验
        byte[] out = new byte[12 + sectionLen + 4];
        out[0] = 'T'; out[1] = 'T'; out[2] = 'X'; out[3] = '2';
        out[4] = (byte) TritCodec.KIND_TEX;
        out[5] = 0;
        out[6] = 0; out[7] = 0; // reserved u16 LE = 0
        putU32LE(out, 8, sectionLen);
        System.arraycopy(section, 0, out, 12, sectionLen);
        putU32LE(out, 12 + sectionLen, (int) (value & 0xffffffffL));
        return out;
    }

    static int[] concatBlockTrits(byte[][] blocks) {
        int[] out = new int[blocks.length * TritCodec.TRITS_PER_BLOCK];
        int k = 0;
        for (byte[] b : blocks) {
            int[] t = blockTrits(b);
            System.arraycopy(t, 0, out, k, t.length);
            k += t.length;
        }
        return out;
    }

    static int[] concatAllBlockTrits(TritCodec.TritTexture[] levels) {
        int total = 0;
        for (TritCodec.TritTexture t : levels) total += t.blocks.length * TritCodec.TRITS_PER_BLOCK;
        int[] out = new int[total];
        int k = 0;
        for (TritCodec.TritTexture t : levels)
            for (byte[] b : t.blocks) {
                int[] tr = blockTrits(b);
                System.arraycopy(tr, 0, out, k, tr.length);
                k += tr.length;
            }
        return out;
    }

    static void putU32LE(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    /** 便捷：RGBA → 单级 TTX2。 */
    public static byte[] rgbaToTtx2(byte[] rgba, int w, int h) {
        return toTtx2Tex(encodeRgba(rgba, w, h));
    }

    /**
     * RGBA → 完整 mip 链 TTX2（细→粗）。
     *
     * 容器/编码器本就支持多级（toTtx2Tex 是 varargs），此前产物只有 1 级
     * 是因为生成器走了上面的单级便捷函数，导致 decodeMipChain 只能本地降级补链。
     * 降采样算法直接复用 TtxTextureProvider.buildMipChain，保证与解码侧完全一致。
     */
    public static byte[] rgbaToTtx2Mipped(byte[] rgba, int w, int h) {
        List<TtxTextureProvider.Rgba> chain =
            TtxTextureProvider.buildMipChain(new TtxTextureProvider.Rgba(w, h, rgba));
        TritCodec.TritTexture[] levels = new TritCodec.TritTexture[chain.size()];
        for (int i = 0; i < chain.size(); i++) {
            TtxTextureProvider.Rgba r = chain.get(i);
            levels[i] = encodeRgba(r.rgba, r.width, r.height);
        }
        return toTtx2Tex(levels);
    }

    /** 便捷：TritTexture 各级 → 尺寸串（调试用）。 */
    public static String levelDims(byte[] ttx2) {
        TritCodec.Ttx2Document d = TritCodec.fromTtx2(ttx2);
        StringBuilder sb = new StringBuilder();
        for (TritCodec.TritTexture t : d.levels) {
            if (sb.length() > 0) sb.append(",");
            sb.append(t.width).append("x").append(t.height);
        }
        return sb.toString();
    }
}
