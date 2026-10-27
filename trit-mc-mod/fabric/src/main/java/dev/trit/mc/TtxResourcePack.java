package dev.trit.mc;

import dev.trit.mc.texture.TtxTextureProvider;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 运行时接管 MC 材质加载：拦截指定 Identifier 的 .png 请求，改为解码同名的
 * trit-texture TTX/TTX2 容器，再以内存 PNG 流喂回 MC 标准资源管线。
 *
 * 这是「MOD 形式整体优化材质」的运行时接管路径（区别于 TritMod 路线 A 的「打包期烘焙」）。
 * 走标准 ResourcePack 接口即可生效；TTX 的磁盘体积收益（2.4bpp）仍然拿到。
 * 真正的显存收益（绕过 PNG 解码、直接 NativeImage）走 TritMod 里的 NativeImage 深路径。
 *
 * 用法：覆盖 vanilla 纹理时 namespace 传 "minecraft"（如 new TtxResourcePack("trit","minecraft",overrides)），
 * 并通过 MinecraftClient.getInstance().getResourcePackManager().registerProvider(...) 注入（见 TritMod.registerRuntimeProvider）。
 */
public final class TtxResourcePack implements ResourcePack {

    /** 一个覆盖规则：把 targetId(.png) 替换为 baseName 对应的 TTX（albedo + 可选 PBR）。 */
    public static final class TtxOverride {
        public final Identifier targetId;     // 例：minecraft:textures/block/stone.png
        public final String baseName;         // 例：stone（对应 stone_normal.ttx2 ...）
        public final byte[] albedo;           // 必填
        public final byte[] normal, rough, metal, ao; // 可选，可为 null
        public TtxOverride(Identifier targetId, String baseName, byte[] albedo,
                       byte[] normal, byte[] rough, byte[] metal, byte[] ao) {
            this.targetId = targetId; this.baseName = baseName; this.albedo = albedo;
            this.normal = normal; this.rough = rough; this.metal = metal; this.ao = ao;
        }
    }

    private final String name;
    private final String namespace;
    private final Map<Identifier, byte[]> pngCache;

    public TtxResourcePack(String name, String namespace, List<TtxOverride> overrides) {
        this.name = name;
        this.namespace = namespace;
        this.pngCache = new java.util.HashMap<>();
        for (TtxOverride o : overrides) {
            if (o.albedo != null)
                pngCache.put(o.targetId, toPng(TtxTextureProvider.decode(o.albedo)));
        }
    }

    @Override
    public InputSupplier<InputStream> openRoot(String... paths) {
        return () -> { throw new FileNotFoundException(String.join("/", paths)); };
    }

    @Override
    public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
        byte[] png = pngCache.get(id);
        if (png == null) return () -> { throw new FileNotFoundException(id.toString()); };
        byte[] data = png;
        return () -> new ByteArrayInputStream(data);
    }

    @Override
    public void findResources(ResourceType type, String namespace, String prefix,
                             ResourcePack.ResultConsumer consumer) {
        for (Map.Entry<Identifier, byte[]> e : pngCache.entrySet()) {
            Identifier id = e.getKey();
            if (!id.getNamespace().equals(namespace)) continue;
            String path = id.getPath();
            if (!path.startsWith(prefix)) continue;
            byte[] data = e.getValue();
            consumer.accept(id, () -> new ByteArrayInputStream(data));
        }
    }

    @Override
    public Set<String> getNamespaces(ResourceType type) {
        return Set.of(namespace);
    }

    @Override
    public <T> T parseMetadata(net.minecraft.resource.metadata.ResourceMetadataSerializer<T> reader) {
        return null;
    }

    @Override
    public ResourcePackInfo getInfo() {
        return new ResourcePackInfo(name, Text.literal(name), ResourcePackSource.BUILTIN, Optional.empty());
    }

    @Override
    public String getId() {
        return name;
    }

    @Override
    public void close() {}

    /** RGBA → PNG（内存字节）。 */
    static byte[] toPng(TtxTextureProvider.Rgba img) {
        try {
            BufferedImage bi = new BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB);
            int[] row = new int[img.width];
            for (int y = 0; y < img.height; y++) {
                for (int x = 0; x < img.width; x++) {
                    int o = (y * img.width + x) * 4;
                    int r = img.rgba[o] & 0xff, g = img.rgba[o + 1] & 0xff, b = img.rgba[o + 2] & 0xff;
                    row[x] = (0xff << 24) | (r << 16) | (g << 8) | b;
                }
                bi.setRGB(0, y, img.width, 1, row, 0, img.width);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(bi, "png", bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
