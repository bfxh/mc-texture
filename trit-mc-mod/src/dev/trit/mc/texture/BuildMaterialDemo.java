package dev.trit.mc.texture;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * 端到端验证 TtxTextureProvider.buildMaterial：
 *  - 对 7 个 base 各组装 albedo + normal + rough + metal + ao 一套材质
 *  - 校验 5 层齐全、尺寸一致、hasPbr()==true
 *  - 量化 TT3 对 PBR 层的压缩损失（解码 PBR vs 原始合成 PBR 的 PSNR）
 *  - 把解码后的材质层落 PNG 到 verify-java/ 供肉眼核对
 *  - 顺带验证 mip 链：decodeMipChain + buildMipChain（"整体优化材质"含各向异性/mip）
 */
public final class BuildMaterialDemo {

    private static final List<String> NAMES = Arrays.asList(
        "stone", "dirt", "grass_block_top", "oak_log", "coal_ore", "water", "lava");

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0]
            : "D:/KF/.yanagent/mc-texture/pack/opt/ttx";
        String outDir = dir + "/verify-java";
        new File(outDir).mkdirs();

        TtxTextureProvider.BytesLoader loader = name -> {
            File f = new File(dir, name);
            return f.exists() ? Files.readAllBytes(f.toPath()) : null;
        };

        int pass = 0, fail = 0;
        System.out.printf("buildMaterial 端到端验证：dir=%s%n%n", dir);
        for (String name : NAMES) {
            TtxTextureProvider.Material m = TtxTextureProvider.buildMaterial(name, loader);
            boolean ok = m.albedo != null && m.normal != null && m.roughness != null
                && m.metalness != null && m.ao != null && m.hasPbr();
            if (!ok) { fail++; System.out.printf("%-16s 字段缺失 %s%n", name, describe(m)); continue; }

            // 尺寸一致性
            int w = m.albedo.width, h = m.albedo.height;
            boolean dimsOk = m.normal.width == w && m.normal.height == h
                && m.roughness.width == w && m.roughness.height == h
                && m.metalness.width == w && m.metalness.height == h
                && m.ao.width == w && m.ao.height == h;
            if (!dimsOk) { fail++; System.out.printf("%-16s 尺寸不一致%n", name); continue; }

            // 量化 TT3 对 PBR 的损失：解码 PBR vs 原始合成 PBR
            PngIO.Rgba albedo = PngIO.read(dir + "/" + name + "_512x510.png");
            byte[] lum = new byte[w * h];
            for (int i = 0; i < w * h; i++) {
                int r = albedo.rgba[i * 4] & 0xff, g = albedo.rgba[i * 4 + 1] & 0xff, b = albedo.rgba[i * 4 + 2] & 0xff;
                lum[i] = (byte) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
            }
            byte[] origNormal = GenPbr.buildNormal(lum, w, h);
            byte[] origRough = GenPbr.buildRough(lum, w, h);
            double psnrN = PngIO.psnrRgb(m.normal.rgba, origNormal);
            double psnrR = PngIO.psnrRgb(m.roughness.rgba, origRough);

            // 落 PNG（TtxTextureProvider.Rgba → PngIO.Rgba 适配器）
            PngIO.write(wrap(m.albedo), outDir + "/" + name + "_mat_albedo.png");
            PngIO.write(wrap(m.normal), outDir + "/" + name + "_mat_normal.png");
            PngIO.write(wrap(m.roughness), outDir + "/" + name + "_mat_rough.png");
            PngIO.write(wrap(m.metalness), outDir + "/" + name + "_mat_metal.png");
            PngIO.write(wrap(m.ao), outDir + "/" + name + "_mat_ao.png");

            if (dimsOk && Double.isFinite(psnrN) && Double.isFinite(psnrR)) pass++;
            else fail++;
            System.out.printf("%-16s %dx%d  albedo/normal/rough/metal/ao 全齐  PSNR(normal)=%.1f dB  PSNR(rough)=%.1f dB  %s%n",
                name, w, h, psnrN, psnrR, ok ? "OK" : "FAIL");
        }

        // mip 链验证（取 stone）
        System.out.printf("%n=== mip 链验证 (stone) ===%n");
        byte[] stone = Files.readAllBytes(new File(dir, "stone.ttx2").toPath());
        List<TtxTextureProvider.Rgba> mip = TtxTextureProvider.decodeMipChain(stone);
        if (mip.size() == 1) mip = TtxTextureProvider.buildMipChain(mip.get(0));
        System.out.printf("stone 原始 %dx%d → mip 级 %d 级：",
            mip.get(0).width, mip.get(0).height, mip.size());
        for (TtxTextureProvider.Rgba lvl : mip) System.out.printf(" %dx%d", lvl.width, lvl.height);
        System.out.printf("%n");

        System.out.printf("%n==== buildMaterial: %d passed, %d failed ====%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    static String describe(TtxTextureProvider.Material m) {
        return String.format("[albedo=%b normal=%b rough=%b metal=%b ao=%b pbr=%b]",
            m.albedo != null, m.normal != null, m.roughness != null, m.metalness != null, m.ao != null, m.hasPbr());
    }

    static PngIO.Rgba wrap(TtxTextureProvider.Rgba r) {
        return new PngIO.Rgba(r.width, r.height, r.rgba);
    }
}
