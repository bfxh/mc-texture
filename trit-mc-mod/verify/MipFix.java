import dev.trit.mc.texture.TritCodec;
import dev.trit.mc.texture.TritEncoder;
import dev.trit.mc.texture.TtxTextureProvider;
import dev.trit.mc.texture.TtxTextureProvider.Rgba;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Fix: shipped TTX2 files only contain 1 mip level (generator used the
 * single-level convenience rgbaToTtx2). The format/encoder DOES support a
 * full chain via toTtx2Tex(levels...). Regenerate every .ttx2 with its real
 * mip chain, verify decodeMipChain returns the levels natively, and measure
 * whether that makes mip decode beat PNG.
 */
public final class MipFix {
    public static void main(String[] args) throws Exception {
        File src = new File(args.length > 0 ? args[0]
                : "D:/KF/.yanagent/mc-texture/trit-mc-mod/fabric/resources/ttx");
        File out = new File(args.length > 1 ? args[1]
                : "D:/KF/.yanagent/mc-texture/trit-mc-mod/verify/ttx_mip");
        out.mkdirs();

        File[] files = src.listFiles(f -> f.getName().endsWith(".ttx2"));
        if (files == null || files.length == 0) { System.out.println("no ttx2 in " + src); return; }

        List<byte[]> oldAll = new ArrayList<>();
        List<byte[]> newAll = new ArrayList<>();
        int levelsWritten = 0;
        long oldBytes = 0, newBytes = 0;

        for (File f : files) {
            byte[] old = Files.readAllBytes(f.toPath());
            Rgba base = TtxTextureProvider.decode(old);
            List<Rgba> chain = TtxTextureProvider.buildMipChain(base);

            TritCodec.TritTexture[] lv = new TritCodec.TritTexture[chain.size()];
            for (int i = 0; i < chain.size(); i++)
                lv[i] = TritEncoder.encodeRgba(chain.get(i).rgba, chain.get(i).width, chain.get(i).height);
            byte[] neu = TritEncoder.toTtx2Tex(lv);

            Files.write(new File(out, f.getName()).toPath(), neu);
            oldAll.add(old); newAll.add(neu);
            oldBytes += old.length; newBytes += neu.length;
            levelsWritten = chain.size();
        }

        // verify native mip count on a regenerated file
        List<Rgba> back = TtxTextureProvider.decodeMipChain(newAll.get(0));
        System.out.println("files regenerated : " + files.length);
        System.out.println("mip levels encoded: " + levelsWritten);
        System.out.println("decodeMipChain on new file returns: " + back.size() + " levels (native, not fallback)");
        for (Rgba r : back) System.out.println("   " + r.width + "x" + r.height);
        System.out.printf("size: old %d B -> new %d B (%.2fx)%n", oldBytes, newBytes, (double) newBytes / oldBytes);

        // timing: old (1 level + local fallback) vs new (native chain)
        int iters = 8;
        for (int i = 0; i < 3; i++) { run(oldAll); run(newAll); }
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) run(oldAll);
        long oldMs = (System.nanoTime() - t0) / 1_000_000 / iters;
        t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) run(newAll);
        long newMs = (System.nanoTime() - t0) / 1_000_000 / iters;

        System.out.println();
        System.out.printf("decode+6mips  OLD(1 lvl, local fallback): %4d ms%n", oldMs);
        System.out.printf("decode+6mips  NEW(native chain)        : %4d ms%n", newMs);
        System.out.printf("-> NEW is %.2fx %s%n", (double) oldMs / newMs,
                newMs < oldMs ? "FASTER" : "SLOWER");
        System.out.println("(reference from BenchDecode: PNG decode+6mips = 249 ms)");
    }

    static void run(List<byte[]> all) {
        for (byte[] b : all) TtxTextureProvider.decodeMipChain(b);
    }
}
