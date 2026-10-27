package dev.trit.mc.texture;

import java.util.ArrayList;
import java.util.List;

/**
 * 材质优化运行时核心（不依赖 Minecraft API，可独立测试）。
 *
 * 把 trit-texture 的 TTX/TTX2 容器接到 MC 材质管线：
 *  - albedo：解码 TTX2 kind=TEX 的 level 0（或 TTX1 单级）
 *  - mip：优先用 TTX2 自带 mip 链；无则本地 2x2 盒式降采样补齐
 *  - PBR：一个材质 = albedo + 可选 normal/rough/metal/ao 多张 TTX（labPBR 命名），
 *          整体打包进一个「材质集」，取代散落的 PNG
 *  - 分辨率：按方块/群系选择不同分辨率的 TTX 变体（TTX 固定分辨率，运行时挑文件）
 */
public final class TtxTextureProvider {

    /** 一张解码后的 RGBA 图。 */
    public static final class Rgba {
        public final int width, height;
        public final byte[] rgba;
        public Rgba(int width, int height, byte[] rgba) { this.width = width; this.height = height; this.rgba = rgba; }
    }

    /** 一套材质：albedo 必填，其余可选（labPBR 标准）。 */
    public static final class Material {
        public final Rgba albedo;
        public final Rgba normal;   // 切线空间法线（RGB）
        public final Rgba roughness;// 粗糙度（R）
        public final Rgba metalness;// 金属度（R）
        public final Rgba ao;       // 环境光遮蔽（R）
        public Material(Rgba albedo, Rgba normal, Rgba roughness, Rgba metalness, Rgba ao) {
            this.albedo = albedo; this.normal = normal; this.roughness = roughness;
            this.metalness = metalness; this.ao = ao;
        }
        public boolean hasPbr() { return normal != null && roughness != null; }
    }

    private TtxTextureProvider() {}

    /** 解码单个 TTX1/TTX2 文件 → 最细级 RGBA。 */
    public static Rgba decode(byte[] container) {
        if (container.length >= 4 && container[0] == 'T' && container[1] == 'T'
                && container[2] == 'X' && container[3] == '2') {
            TritCodec.Ttx2Document doc = TritCodec.fromTtx2(container);
            if (doc.kind != TritCodec.KIND_TEX) throw new IllegalArgumentException("TTX2 kind is MOD, not TEX");
            TritCodec.TritTexture t = doc.levels[0];
            return new Rgba(t.width, t.height, TritCodec.decodeRgba(t));
        }
        TritCodec.TritTexture t = TritCodec.fromTtx1(container);
        return new Rgba(t.width, t.height, TritCodec.decodeRgba(t));
    }

    /** 解码 TTX2 的全部 mip 级（细→粗）。非 TTX2 或单级则回退到本地生成。 */
    public static List<Rgba> decodeMipChain(byte[] container) {
        List<Rgba> out = new ArrayList<>();
        if (container.length >= 4 && container[0] == 'T' && container[3] == '2') {
            TritCodec.Ttx2Document doc = TritCodec.fromTtx2(container);
            if (doc.kind == TritCodec.KIND_TEX && doc.levels.length > 1) {
                for (TritCodec.TritTexture t : doc.levels)
                    out.add(new Rgba(t.width, t.height, TritCodec.decodeRgba(t)));
                return out;
            }
        }
        Rgba base = decode(container);
        out.add(base);
        return out; // 单级：调用方用 buildMipChain 补足
    }

    /** 本地 2x2 盒式降采样，补足 mip 链（与 trit-texture convert.rs 同算法）。 */
    public static List<Rgba> buildMipChain(Rgba base) {
        List<Rgba> chain = new ArrayList<>();
        chain.add(base);
        Rgba cur = base;
        while (cur.width >= 2 * TritCodec.BLOCK_WIDTH && cur.height >= 2 * TritCodec.BLOCK_HEIGHT) {
            int w = (cur.width / 2 / TritCodec.BLOCK_WIDTH) * TritCodec.BLOCK_WIDTH;
            int h = (cur.height / 2 / TritCodec.BLOCK_HEIGHT) * TritCodec.BLOCK_HEIGHT;
            if (w >= cur.width || h >= cur.height) break;
            cur = downsample(cur, w, h);
            chain.add(cur);
        }
        return chain;
    }

    private static Rgba downsample(Rgba src, int w, int h) {
        byte[] out = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                long[] sum = {0, 0, 0};
                for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
                    int sx = Math.min(x * 2 + dx, src.width - 1);
                    int sy = Math.min(y * 2 + dy, src.height - 1);
                    int o = (sy * src.width + sx) * 4;
                    for (int c = 0; c < 3; c++) sum[c] += src.rgba[o + c] & 0xff;
                }
                int o = (y * w + x) * 4;
                for (int c = 0; c < 3; c++) out[o + c] = (byte) (sum[c] / 4);
                out[o + 3] = (byte) 255;
            }
        }
        return new Rgba(w, h, out);
    }

    /**
     * 组装一套材质：baseName 关联
     *   baseName.ttx2        (albedo, 必填)
     *   baseName_normal.ttx2 (可选)
     *   baseName_rough.ttx2  (可选)
     *   baseName_metal.ttx2  (可选)
     *   baseName_ao.ttx2     (可选)
     * loader 负责按命名取字节（classpath/资源包/磁盘皆可）。
     */
    public interface BytesLoader { byte[] load(String fileName) throws Exception; }

    public static Material buildMaterial(String baseName, BytesLoader loader) throws Exception {
        Rgba albedo = decode(loader.load(baseName + ".ttx2"));
        Rgba normal = tryDecode(loader, baseName + "_normal.ttx2");
        Rgba rough = tryDecode(loader, baseName + "_rough.ttx2");
        Rgba metal = tryDecode(loader, baseName + "_metal.ttx2");
        Rgba ao = tryDecode(loader, baseName + "_ao.ttx2");
        return new Material(albedo, normal, rough, metal, ao);
    }

    private static Rgba tryDecode(BytesLoader loader, String name) {
        try {
            byte[] b = loader.load(name);
            if (b == null) return null;
            return decode(b);
        } catch (Exception e) {
            return null;
        }
    }
}
