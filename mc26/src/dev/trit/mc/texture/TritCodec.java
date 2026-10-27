package dev.trit.mc.texture;

import java.util.Arrays;

/**
 * 忠实移植 trit-texture 的 TT3 编解码 + TTX1/TTX2 容器（仅解码侧）。
 *
 * 逐字节对齐 Rust crate（D:/KF/trit/crates/trit-texture）：
 *  - lib.rs：块结构、RGB565、3 级调色板、trit 打包（低位 trit 在前）
 *  - ttx.rs：TTX1 容器 + GF(3) CRC-3（生成子 x^3+2x+1 的互反式，低次在前）
 *  - ttx2/mod.rs：TTX2 容器 + GF(3) CRC-16（g=x^16+x^7+2，低次在前）
 *
 * 用途：Minecraft Mod 运行时把 .ttx/.tt2 解码成 RGBA，绕过 PNG 图集。
 */
public final class TritCodec {

    /**
     * 块尺寸。**4×8 而非原先的 4×10**：块高必须是常见纹理边长(16/32/64/128/256/512)的约数，
     * 而 10 不整除其中任何一个 —— 原格式连一张 MC 纹理都编码不了（encodeRgba 直接抛异常）。
     * 4×8 覆盖全部 2 的幂尺寸，且块比 4×4 大，端点开销摊得更薄。
     */
    public static final int BLOCK_WIDTH = 4;
    public static final int BLOCK_HEIGHT = 8;
    public static final int BLOCK_PIXELS = BLOCK_WIDTH * BLOCK_HEIGHT;   // 32
    public static final int TRITS_PER_BLOCK = BLOCK_PIXELS;              // 32
    /** 5 trit/字节，向上取整（32 trit -> 7 字节，末字节留 3 个空槽）。 */
    public static final int TRIT_PAYLOAD_BYTES = (TRITS_PER_BLOCK + 4) / 5; // 7
    public static final int TRIT_BLOCK_BYTES = 4 + TRIT_PAYLOAD_BYTES;   // 11

    // ----- 三进制 -----
    private enum T {
        F, U, T;
        static T of(int d) { return d == 0 ? F : (d == 1 ? U : T); }
    }

    // ----- RGB565 展开（左对齐补位，0/31 满量程） -----
    static int[] unpackRgb565(int v) {
        int r5 = (v >> 11) & 0x1f;
        int g6 = (v >> 5) & 0x3f;
        int b5 = v & 0x1f;
        int r = (r5 << 3) | (r5 >> 2);
        int g = (g6 << 2) | (g6 >> 4);
        int b = (b5 << 3) | (b5 >> 2);
        return new int[]{r, g, b};
    }

    // trit 载荷中第 i 个 trit（块内偏移 4 字节端点；低位 trit 在前）：digit = (byte / 3^slot) % 3
    static int payloadDigit(byte[] block, int i) {
        int b = block[4 + i / 5] & 0xff;
        int slot = i % 5;
        return (b / pow3(slot)) % 3;
    }

    static int pow3(int n) {
        int r = 1;
        for (int k = 0; k < n; k++) r *= 3;
        return r;
    }

    // ----- GF(3) 多项式长除（系数低次在前，常数项非零） -----
    static int mod3(int x) { return ((x % 3) + 3) % 3; }

    static int[] gf3Rem(int[] digits, int[] poly) {
        int degree = poly.length - 1;
        int[] buf = new int[digits.length + degree];
        System.arraycopy(digits, 0, buf, 0, digits.length);
        for (int i = 0; i <= buf.length - poly.length; i++) {
            int coeff = buf[i];
            if (coeff != 0) {
                for (int j = 0; j < poly.length; j++) {
                    buf[i + j] = mod3(buf[i + j] - coeff * poly[j]);
                }
            }
        }
        return Arrays.copyOfRange(buf, buf.length - degree, buf.length);
    }

    // 一个块的全部索引 trit（后 8 字节）
    static int[] blockTrits(byte[] block) {
        int[] out = new int[TRITS_PER_BLOCK];
        for (int i = 0; i < TRITS_PER_BLOCK; i++) {
            out[i] = payloadDigit(block, i);
        }
        return out;
    }

    // ----- 3 级调色板：[e0, mid, e1] -----
    static int[][] paletteFromEndpoints(int e0, int e1) {
        int[] a = unpackRgb565(e0);
        int[] b = unpackRgb565(e1);
        int[] mid = new int[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2};
        return new int[][]{a, mid, b};
    }

    /** TT3 解码：单块（4 字节端点 + trit 载荷）→ 写入 out（RGBA，alpha=255）。 */
    public static void decodeBlock(byte[] block, int width, int height,
                                   int blockCol, int blockRow, byte[] out) {
        int e0 = ((block[0] & 0xff) << 8) | (block[1] & 0xff); // big-endian
        int e1 = ((block[2] & 0xff) << 8) | (block[3] & 0xff);
        int[][] pal = paletteFromEndpoints(e0, e1);
        for (int i = 0; i < BLOCK_PIXELS; i++) {
            int d = payloadDigit(block, i); // 0/1/2
            int x = blockCol * BLOCK_WIDTH + i % BLOCK_WIDTH;
            int y = blockRow * BLOCK_HEIGHT + i / BLOCK_WIDTH;
            if (x >= width || y >= height) continue;
            int off = (y * width + x) * 4;
            out[off] = (byte) pal[d][0];
            out[off + 1] = (byte) pal[d][1];
            out[off + 2] = (byte) pal[d][2];
            out[off + 3] = (byte) 255;
        }
    }

    /** 整张 TritTexture → RGBA。 */
    public static byte[] decodeRgba(TritTexture tex) {
        byte[] out = new byte[tex.width * tex.height * 4];
        int cols = tex.width / BLOCK_WIDTH;
        for (int bi = 0; bi < tex.blocks.length; bi++) {
            decodeBlock(tex.blocks[bi], tex.width, tex.height, bi % cols, bi / cols, out);
        }
        return out;
    }

    /** 解码到调用方缓冲（热路径复用，免整图分配）。 */
    public static void decodeRgbaInto(TritTexture tex, byte[] out) {
        int cols = tex.width / BLOCK_WIDTH;
        for (int bi = 0; bi < tex.blocks.length; bi++) {
            decodeBlock(tex.blocks[bi], tex.width, tex.height, bi % cols, bi / cols, out);
        }
    }

    public static final class TritTexture {
        public final int width, height;
        public final byte[][] blocks;
        public TritTexture(int width, int height, byte[][] blocks) {
            this.width = width;
            this.height = height;
            this.blocks = blocks;
        }
    }

    // ===================== TTX1 =====================
    private static final byte[] TTX1_MAGIC = {'T', 'T', 'X', '1'};
    static final int[] TTX1_GEN = {1, 0, 2, 1}; // 低次在前

    private static int readU32LE(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
             | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    public static TritTexture fromTtx1(byte[] bytes) {
        if (bytes.length < 16 + 1) throw new IllegalArgumentException("TTX1 truncated");
        if (!magicEq(bytes, 0, TTX1_MAGIC)) throw new IllegalArgumentException("TTX1 magic mismatch");
        int width = readU32LE(bytes, 4);
        int height = readU32LE(bytes, 8);
        int blocks = readU32LE(bytes, 12);
        if (width == 0 || width % BLOCK_WIDTH != 0) throw new IllegalArgumentException("TTX1 width not multiple of 4");
        if (height == 0 || height % BLOCK_HEIGHT != 0) throw new IllegalArgumentException("TTX1 height not multiple of " + BLOCK_HEIGHT);
        int expected = (width / BLOCK_WIDTH) * (height / BLOCK_HEIGHT);
        if (blocks != expected) throw new IllegalArgumentException("TTX1 block count mismatch");
        int payload = blocks * TRIT_BLOCK_BYTES;
        if (bytes.length != 16 + payload + 1) throw new IllegalArgumentException("TTX1 length mismatch");
        byte[][] bl = new byte[blocks][];
        for (int i = 0; i < blocks; i++) {
            bl[i] = Arrays.copyOfRange(bytes, 16 + i * TRIT_BLOCK_BYTES, 16 + (i + 1) * TRIT_BLOCK_BYTES);
        }
        int stored = bytes[16 + payload] & 0xff;
        int calc = ttx1Checksum(bl);
        if (stored != calc) throw new IllegalArgumentException("TTX1 checksum mismatch stored=" + stored + " calc=" + calc);
        return new TritTexture(width, height, bl);
    }

    static int ttx1Checksum(byte[][] blocks) {
        int[] trits = concatBlockTrits(blocks);
        int[] rem = gf3Rem(trits, TTX1_GEN); // degree 3
        return rem[0] * 9 + rem[1] * 3 + rem[2];
    }

    // ===================== TTX2 =====================
    private static final byte[] TTX2_MAGIC = {'T', 'T', 'X', '2'};
    public static final int KIND_TEX = 0;
    public static final int KIND_MOD = 1;
    static final int[] TTX2_GEN16 = {2, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1}; // 低次在前

    public static final class Ttx2Document {
        public final int kind;
        public final TritTexture[] levels; // kind=TEX
        public Ttx2Document(int kind, TritTexture[] levels) { this.kind = kind; this.levels = levels; }
    }

    public static Ttx2Document fromTtx2(byte[] bytes) {
        if (bytes.length < 12 + 4) throw new IllegalArgumentException("TTX2 truncated");
        if (!magicEq(bytes, 0, TTX2_MAGIC)) throw new IllegalArgumentException("TTX2 magic mismatch");
        int kind = bytes[4] & 0xff;
        int flags = bytes[5] & 0xff;
        int reserved = (bytes[6] & 0xff) | ((bytes[7] & 0xff) << 8);
        if (kind != KIND_TEX && kind != KIND_MOD) throw new IllegalArgumentException("TTX2 unknown kind " + kind);
        if (flags != 0 || reserved != 0) throw new IllegalArgumentException("TTX2 flags/reserved nonzero");
        int sectionLen = readU32LE(bytes, 8);
        int sectionEnd = 12 + sectionLen + 4;
        if (bytes.length != sectionEnd) throw new IllegalArgumentException("TTX2 length mismatch");
        byte[] section = Arrays.copyOfRange(bytes, 12, 12 + sectionLen);
        TritTexture[] levels;
        if (kind == KIND_TEX) {
            levels = decodeTextureSection(section);
        } else {
            levels = null; // MOD（三值模型）解码不在纹理管线内；运行时按需扩展
        }
        // 校验：TEX 把所有级块 trit 串起来；MOD 用 model_trits（此处不验证 MOD）
        if (kind == KIND_TEX) {
            int[] trits = concatAllBlockTrits(levels);
            int[] rem = gf3Rem(trits, TTX2_GEN16);
            long value = 0;
            for (int i = 0; i < rem.length; i++) value += (long) rem[i] * pow3(i);
            int stored = readU32LE(bytes, 12 + sectionLen);
            if ((value & 0xffffffffL) != (stored & 0xffffffffL))
                throw new IllegalArgumentException("TTX2 checksum mismatch");
        }
        return new Ttx2Document(kind, levels);
    }

    static TritTexture[] decodeTextureSection(byte[] section) {
        if (section.length < 4) throw new IllegalArgumentException("TTX2 section truncated");
        int levelCount = readU32LE(section, 0);
        if (levelCount == 0) throw new IllegalArgumentException("TTX2 empty");
        int cursor = 4;
        TritTexture[] levels = new TritTexture[levelCount];
        for (int l = 0; l < levelCount; l++) {
            if (cursor + 8 > section.length) throw new IllegalArgumentException("TTX2 level head truncated");
            int w = readU32LE(section, cursor);
            int h = readU32LE(section, cursor + 4);
            cursor += 8;
            if (w == 0 || w % BLOCK_WIDTH != 0) throw new IllegalArgumentException("TTX2 width not multiple of 4");
            if (h == 0 || h % BLOCK_HEIGHT != 0) throw new IllegalArgumentException("TTX2 height not multiple of " + BLOCK_HEIGHT);
            int blocks = (w / BLOCK_WIDTH) * (h / BLOCK_HEIGHT);
            int payload = blocks * TRIT_BLOCK_BYTES;
            if (cursor + payload > section.length) throw new IllegalArgumentException("TTX2 level payload truncated");
            byte[][] bl = new byte[blocks][];
            for (int i = 0; i < blocks; i++) {
                bl[i] = Arrays.copyOfRange(section, cursor + i * TRIT_BLOCK_BYTES, cursor + (i + 1) * TRIT_BLOCK_BYTES);
            }
            cursor += payload;
            levels[l] = new TritTexture(w, h, bl);
        }
        // mip 单调性 + 形状（与写侧共用规则）
        for (int l = 0; l < levels.length; l++) {
            TritTexture t = levels[l];
            if (t.width == 0 || t.height == 0 || t.width % BLOCK_WIDTH != 0 || t.height % BLOCK_HEIGHT != 0)
                throw new IllegalArgumentException("TTX2 level shape");
            if (l > 0) {
                TritTexture p = levels[l - 1];
                if (!(t.width <= p.width && t.height <= p.height && (t.width < p.width || t.height < p.height)))
                    throw new IllegalArgumentException("TTX2 mip not strictly decreasing");
            }
        }
        return levels;
    }

    // ----- 工具 -----
    private static boolean magicEq(byte[] bytes, int off, byte[] magic) {
        for (int i = 0; i < 4; i++) if (bytes[off + i] != magic[i]) return false;
        return true;
    }

    private static int[] concatBlockTrits(byte[][] blocks) {
        int[] out = new int[blocks.length * TRITS_PER_BLOCK];
        int k = 0;
        for (byte[] b : blocks) {
            int[] t = blockTrits(b);
            System.arraycopy(t, 0, out, k, t.length);
            k += t.length;
        }
        return out;
    }

    private static int[] concatAllBlockTrits(TritTexture[] levels) {
        int total = 0;
        for (TritTexture t : levels) total += t.blocks.length * TRITS_PER_BLOCK;
        int[] out = new int[total];
        int k = 0;
        for (TritTexture t : levels)
            for (byte[] b : t.blocks) {
                int[] tr = blockTrits(b);
                System.arraycopy(tr, 0, out, k, tr.length);
                k += tr.length;
            }
        return out;
    }
}
