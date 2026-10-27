import dev.trit.mc.texture.TtxTextureProvider;
import dev.trit.mc.texture.TtxTextureProvider.Rgba;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Headless benchmark: is TTX actually faster to decode than PNG?
 * Compares (a) single-level decode and (b) full 6-level mip chain,
 * which is the only remaining plausible runtime benefit of TTX in Minecraft
 * (storage is NOT better than PNG - see layer-wise size comparison).
 */
public final class BenchDecode {
    public static void main(String[] args) throws Exception {
        String jar = args.length > 0 ? args[0]
            : "E:/MC/MC/profiles/1.21.4-Fabric/mods/trit-texture-0.1.0.jar";

        List<byte[]> ttx = new ArrayList<>();
        List<byte[]> png = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try (ZipFile zf = new ZipFile(jar)) {
            for (var e = zf.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                String n = ze.getName();
                if (n.startsWith("ttx/") && n.endsWith(".ttx2")) {
                    ttx.add(zf.getInputStream(ze).readAllBytes());
                    names.add(n.substring(n.lastIndexOf('/') + 1));
                }
            }
            // matching baked PNGs for the same textures
            for (String nm : new ArrayList<>(names)) {
                String base = nm.substring(0, nm.length() - 5);
                String baked = base.equals("water") || base.equals("lava") ? base + "_still"
                        : (base.endsWith("_normal") || base.endsWith("_rough")
                           || base.endsWith("_metal") || base.endsWith("_ao"))
                          ? base.replace("water_", "water_still_").replace("lava_", "lava_still_")
                          : base;
                ZipEntry pe = zf.getEntry("resourcepacks/trit_textures/assets/minecraft/textures/block/"
                        + baked + ".png");
                png.add(pe == null ? null : zf.getInputStream(pe).readAllBytes());
            }
        }
        int pairs = 0;
        for (byte[] b : png) if (b != null) pairs++;
        System.out.println("ttx=" + ttx.size() + "  matched png=" + pairs);

        int iters = 8;
        // warmup
        for (int i = 0; i < 3; i++) {
            runTtx(ttx); runPng(png); runTtxMips(ttx); runPngMips(png);
        }

        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) runTtx(ttx);
        long ttxMs = (System.nanoTime() - t0) / 1_000_000 / iters;

        t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) runPng(png);
        long pngMs = (System.nanoTime() - t0) / 1_000_000 / iters;

        t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) runTtxMips(ttx);
        long ttxMipMs = (System.nanoTime() - t0) / 1_000_000 / iters;

        t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) runPngMips(png);
        long pngMipMs = (System.nanoTime() - t0) / 1_000_000 / iters;

        System.out.println();
        System.out.printf("decode 1 level  : TTX %4d ms   PNG %4d ms   -> TTX %.2fx %s%n",
                ttxMs, pngMs, (double) pngMs / ttxMs, ttxMs < pngMs ? "FASTER" : "SLOWER");
        System.out.printf("decode + 6 mips : TTX %4d ms   PNG %4d ms   -> TTX %.2fx %s%n",
                ttxMipMs, pngMipMs, (double) pngMipMs / ttxMipMs, ttxMipMs < pngMipMs ? "FASTER" : "SLOWER");

        // count mip levels TTX provides natively
        List<Rgba> chain = TtxTextureProvider.decodeMipChain(ttx.get(0));
        System.out.println();
        System.out.println("TTX2 native mip levels for " + names.get(0) + " = " + chain.size());
        for (Rgba r : chain) System.out.println("   " + r.width + "x" + r.height);
    }

    static void runTtx(List<byte[]> ttx) {
        for (byte[] b : ttx) TtxTextureProvider.decode(b);
    }

    static void runPng(List<byte[]> png) throws Exception {
        for (byte[] b : png) {
            if (b == null) continue;
            ImageIO.read(new ByteArrayInputStream(b));
        }
    }

    static void runTtxMips(List<byte[]> ttx) {
        for (byte[] b : ttx) TtxTextureProvider.decodeMipChain(b);
    }

    static void runPngMips(List<byte[]> png) throws Exception {
        for (byte[] b : png) {
            if (b == null) continue;
            var bi = ImageIO.read(new ByteArrayInputStream(b));
            int w = bi.getWidth(), h = bi.getHeight();
            byte[] rgba = toRgba(bi);
            TtxTextureProvider.buildMipChain(new Rgba(w, h, rgba));
        }
    }

    static byte[] toRgba(java.awt.image.BufferedImage bi) {
        int w = bi.getWidth(), h = bi.getHeight();
        byte[] out = new byte[w * h * 4];
        int[] px = new int[w];
        for (int y = 0; y < h; y++) {
            bi.getRGB(0, y, w, 1, px, 0, w);
            for (int x = 0; x < w; x++) {
                int v = px[x], o = (y * w + x) * 4;
                out[o] = (byte) (v >> 16 & 0xff);
                out[o + 1] = (byte) (v >> 8 & 0xff);
                out[o + 2] = (byte) (v & 0xff);
                out[o + 3] = (byte) (v >> 24 & 0xff);
            }
        }
        return out;
    }
}
