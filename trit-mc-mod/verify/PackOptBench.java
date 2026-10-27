import dev.trit.mc.texture.TritCodec;
import dev.trit.mc.texture.TritEncoder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 针对「玩家真实材质包」的优化收益实测（不是我自己生成的包）。
 * 测：PNG 解码耗时 vs 转 TTX 后解码耗时，以及体积变化。
 */
public class PackOptBench {
    public static void main(String[] args) throws Exception {
        String zip = args.length > 0 ? args[0]
                : "E:/MC/MC/profiles/1.21.4-Vanilla/resourcepacks/Optimum Realism R4.2.0.zip";
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 400;

        System.out.println("包: " + zip);
        List<byte[]> pngs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements() && pngs.size() < limit) {
                ZipEntry e = en.nextElement();
                if (e.getName().contains("/textures/block/") && e.getName().endsWith(".png")) {
                    byte[] b = zf.getInputStream(e).readAllBytes();
                    // 跳过动画竖条(非方形)与非 2 的幂，保持样本干净
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(b));
                    if (img == null) continue;
                    pngs.add(b);
                    names.add(e.getName().substring(e.getName().lastIndexOf('/') + 1));
                }
            }
        }
        System.out.printf("样本: %d 张 block 纹理%n", pngs.size());

        // --- PNG 解码 ---
        double[] pt = new double[3];
        long raw = 0;
        for (int r = 0; r < 3; r++) {
            long s = System.nanoTime();
            long bytes = 0;
            for (byte[] p : pngs) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(p));
                int[] px = new int[img.getWidth() * img.getHeight()];
                img.getRGB(0, 0, img.getWidth(), img.getHeight(), px, 0, img.getWidth());
                bytes += (long) img.getWidth() * img.getHeight() * 4;
            }
            raw = bytes;
            pt[r] = (System.nanoTime() - s) / 1e6;
        }
        Arrays.sort(pt);

        // --- 编码为 TTX ---
        long s = System.nanoTime();
        List<byte[]> ttxList = new ArrayList<>();
        long ttxBytes = 0, pngBytes = 0;
        for (byte[] p : pngs) {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(p));
            int w = img.getWidth(), h = img.getHeight();
            int[] argb = new int[w * h];
            img.getRGB(0, 0, w, h, argb, 0, w);
            byte[] rgba = new byte[w * h * 4];
            for (int i = 0; i < argb.length; i++) {
                rgba[i * 4]     = (byte) (argb[i] >> 16);
                rgba[i * 4 + 1] = (byte) (argb[i] >> 8);
                rgba[i * 4 + 2] = (byte) argb[i];
                rgba[i * 4 + 3] = (byte) (argb[i] >> 24);
            }
            byte[] t = TritEncoder.rgbaToTtx2Mipped(rgba, w, h);
            ttxList.add(t);
            ttxBytes += t.length;
            pngBytes += p.length;
        }
        double encMs = (System.nanoTime() - s) / 1e6;

        // --- TTX 解码 ---
        double[] tt = new double[3];
        for (int r = 0; r < 3; r++) {
            long st = System.nanoTime();
            for (byte[] t : ttxList) {
                TritCodec.Ttx2Document d = TritCodec.fromTtx2(t);
                TritCodec.decodeRgba(d.levels[0]);
            }
            tt[r] = (System.nanoTime() - st) / 1e6;
        }
        Arrays.sort(tt);

        System.out.println();
        System.out.println("=========== 结果 ===========");
        System.out.printf("解压后 RGBA : %.2f MB%n", raw / 1048576.0);
        System.out.printf("PNG 压缩后  : %.2f MB%n", pngBytes / 1048576.0);
        System.out.printf("TTX(6级mip) : %.2f MB   -> 相对 PNG %.2fx%n",
                ttxBytes / 1048576.0, (double) ttxBytes / pngBytes);
        System.out.printf("PNG 解码    : %.0f ms%n", pt[1]);
        System.out.printf("TTX 解码    : %.0f ms   -> 加速 %.2fx%n", tt[1], pt[1] / tt[1]);
        System.out.printf("TTX 编码(一次性): %.0f ms%n", encMs);
        System.out.println();
        System.out.printf("外推到全包 2195 张：PNG 解码约 %.0f ms，TTX 解码约 %.0f ms，省约 %.0f ms%n",
                pt[1] * 2195.0 / pngs.size(), tt[1] * 2195.0 / pngs.size(),
                (pt[1] - tt[1]) * 2195.0 / pngs.size());
    }
}
