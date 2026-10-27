package dev.trit.mc.texture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 生成真实可加载的 Minecraft resourcepack（由 TTX2 解码而来）。
 *
 * 落盘两处：
 *   dist/trit-texture-rp/                        —— 独立可加载包（丢进 MC resourcepacks/）
 *   fabric/resources/resourcepacks/trit_textures/ —— Mod 内置包来源（gradle build 打进 jar，TritMod 自动启用）
 *
 * 并做三项本机验证：解码层数、写入后 PNG 可独立读回、尺寸吻合。
 */
public final class GenResourcepack {
    public static void main(String[] a) throws Exception {
        Path ttx = Path.of("D:/KF/.yanagent/mc-texture/pack/opt/ttx");
        Path dist = Path.of("D:/KF/.yanagent/mc-texture/trit-mc-mod/dist/trit-texture-rp");
        Path builtin = Path.of("D:/KF/.yanagent/mc-texture/trit-mc-mod/fabric/resources/resourcepacks/trit_textures");

        Map<String, TtxTextureProvider.Rgba> map = TtxResourcePackCore.buildLayerMap(ttx);
        int blocks = 0, pbr = 0;
        for (String key : map.keySet())
            if (TtxResourcePackCore.isPbrLayer(key.substring("minecraft:".length()))) pbr++; else blocks++;
        System.out.printf("[gen] decoded %d layers (%d block albedo + %d PBR)%n", map.size(), blocks, pbr);
        for (Map.Entry<String, TtxTextureProvider.Rgba> e : map.entrySet())
            System.out.printf("  %-48s %dx%d%n", e.getKey(), e.getValue().width, e.getValue().height);

        TtxResourcePackCore.writeResourcepack(ttx, dist);
        TtxResourcePackCore.writeResourcepack(ttx, builtin);

        // 验证：dist 下写出的 PNG 能被 ImageIO 独立读回且尺寸一致（证明是真 PNG 而非坏字节）
        int ok = 0;
        for (Map.Entry<String, TtxTextureProvider.Rgba> e : map.entrySet()) {
            String sub = e.getKey().substring("minecraft:".length());
            Path p = dist.resolve("assets/minecraft/" + sub);
            BufferedImage bi = ImageIO.read(new File(p.toUri()));
            if (bi != null && bi.getWidth() == e.getValue().width && bi.getHeight() == e.getValue().height) ok++;
        }
        boolean pass = ok == map.size();
        System.out.printf("[gen] re-read %d/%d PNG ok -> %s%n", ok, map.size(), pass ? "PASS" : "FAIL");
        System.out.println(pass ? "[gen] DONE" : "[gen] FAILED");
        if (!pass) System.exit(1);
    }
}
