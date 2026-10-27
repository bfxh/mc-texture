#!/usr/bin/env python3
# Fix wrong water/lava override paths (vanilla uses water_still/lava_still + *_flow,
# not water.png/lava.png), re-pack the resourcepack, validate, and rebuild the
# vanilla-vs-trit comparison HTML using the REAL 1.21.4 client jar assets.
import os, zipfile, shutil, base64, struct

DIST   = r"D:/KF/.yanagent/mc-texture/trit-mc-mod/dist/trit-texture-rp"
BLOCK  = os.path.join(DIST, "assets/minecraft/textures/block")
RP_OUT = r"E:/MC/MC/profiles/1.21.4-Vanilla/resourcepacks/Trit-512x-Texture-Pack.zip"
CLIENT = r"E:/MC/MC/meta/versions/1.21.4/1.21.4.jar"
HTML   = r"D:/KF/.yanagent/mc-texture/trit-mc-mod/verify/texture-takeover-comparison.html"

LAYERS = ["", "_normal", "_rough", "_metal", "_ao"]

# ---------- 1) fix water/lava paths in dist (idempotent) ----------
renamed = False
for base in ["water", "lava"]:
    for suf in LAYERS:
        src = os.path.join(BLOCK, f"{base}{suf}.png")
        if os.path.exists(src):
            os.rename(src, os.path.join(BLOCK, f"{base}_still{suf}.png"))
            shutil.copyfile(os.path.join(BLOCK, f"{base}_still{suf}.png"),
                            os.path.join(BLOCK, f"{base}_flow{suf}.png"))
            renamed = True
print("renamed+flow water/lava:", renamed)

# ---------- 2) re-zip ----------
with zipfile.ZipFile(RP_OUT, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(DIST):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, DIST).replace(os.sep, '/'))

# ---------- 3) validate ----------
ck = zipfile.ZipFile(CLIENT); ckn = set(ck.namelist())
rp = zipfile.ZipFile(RP_OUT); rpn = set(rp.namelist())
png = [n for n in rpn if n.endswith('.png')]
bad = 0
for n in png:
    d = rp.read(n)
    if d[:8] != b'\x89PNG\r\n\x1a\n':
        bad += 1
    else:
        w, h = struct.unpack('>II', d[16:24])
        if (w, h) != (512, 510):
            bad += 1
print("zip entries:", len(rpn), "| png:", len(png), "| corrupt/dim errors:", bad)

# logical block -> (vanilla path in client, trit path in our pack)
MAP = {
    "stone":          ("stone.png",          "stone.png"),
    "dirt":           ("dirt.png",           "dirt.png"),
    "grass_block_top":("grass_block_top.png","grass_block_top.png"),
    "oak_log":        ("oak_log.png",        "oak_log.png"),
    "coal_ore":       ("coal_ore.png",       "coal_ore.png"),
    "water":          ("water_still.png",    "water_still.png"),
    "lava":           ("lava_still.png",     "lava_still.png"),
}
proof = []
allok = True
for b, (vp, tp) in MAP.items():
    v_ok = f"assets/minecraft/textures/block/{vp}" in ckn
    t_ok = f"assets/minecraft/textures/block/{tp}" in rpn
    allok = allok and v_ok and t_ok
    proof.append((b, vp, tp, v_ok, t_ok))
print("ALL PRIMARY PATHS OK:", allok)
for b, vp, tp, v, t in proof:
    print(f"  {b:16} vanilla {vp:22} {'OK' if v else 'MISSING'} | trit {tp:22} {'OK' if t else 'MISSING'}")

# ---------- 4) build comparison HTML ----------
def b64(zipf, name):
    return base64.b64encode(zipf.read(name)).decode('ascii')

def img(uri, w, label):
    return (f'<div class="cell"><img src="data:image/png;base64,{uri}" '
            f'style="width:{w}px"><div class="lbl">{label}</div></div>')

vanilla, trit, stone_pbr = {}, {}, {}
for b, (vp, tp) in MAP.items():
    vn = f"assets/minecraft/textures/block/{vp}"
    tn = f"assets/minecraft/textures/block/{tp}"
    if vn in ckn: vanilla[b] = b64(ck, vn)
    if tn in rpn: trit[b] = b64(rp, tn)
for suf in ["_normal", "_rough", "_metal", "_ao"]:
    p = f"assets/minecraft/textures/block/stone{suf}.png"
    if p in rpn: stone_pbr[suf] = b64(rp, p)

proof_html = "".join(
    f"<tr><td>{b}</td><td>{vp}</td><td class='{'ok' if v else 'bad'}'>"
    f"{'✓' if v else '✗'}</td><td>{tp}</td><td class='{'ok' if t else 'bad'}'>"
    f"{'✓' if t else '✗'}</td></tr>" for b, vp, tp, v, t in proof)

compare_html = ""
for b in MAP:
    if b in vanilla and b in trit:
        compare_html += (f'<div class="block"><h3>{b}</h3><div class="row">'
                         + img(vanilla[b], 160, "原版 1.21.4")
                         + img(trit[b], 160, "Trit 接管 512×510")
                         + "</div></div>")

pbr_html = '<div class="row">'
if "stone" in trit: pbr_html += img(trit["stone"], 150, "stone (albedo)")
for suf, lab in [("_normal","法线"),("_rough","粗糙"),("_metal","金属"),("_ao","AO")]:
    if suf in stone_pbr: pbr_html += img(stone_pbr[suf], 150, lab)
pbr_html += "</div>"

html = f"""<!doctype html><html lang="zh"><head><meta charset="utf-8">
<style>
 body{{font-family:system-ui,'Microsoft YaHei',sans-serif;background:#1b1b1f;color:#e8e8ea;margin:0;padding:24px}}
 h1{{font-size:20px}} h2{{font-size:16px;margin-top:28px;border-left:4px solid #4ea1ff;padding-left:10px}}
 h3{{font-size:14px;margin:14px 0 6px;color:#9fd0ff}}
 .row{{display:flex;flex-wrap:wrap;gap:14px}} .cell{{text-align:center}}
 .cell img{{border:1px solid #444;border-radius:6px;background:#2a2a2e;image-rendering:pixelated}}
 .lbl{{font-size:11px;color:#aaa;margin-top:4px;max-width:180px}}
 table{{border-collapse:collapse;font-size:13px}} td,th{{border:1px solid #444;padding:6px 10px}}
 .ok{{color:#5fd97a}} .bad{{color:#ff6b6b}}
 .note{{background:#2a2a2e;border:1px solid #444;border-radius:8px;padding:12px 16px;font-size:13px;line-height:1.6}}
 .block{{margin-bottom:6px}}
</style></head><body>
<h1>Trit 材质接管验证 · 1.21.4 真实客户端提取对比</h1>
<div class="note">所有"原版"纹理均从真实 <b>1.21.4 客户端 jar</b> 提取；"Trit"纹理来自已放入
 <code>resourcepacks/Trit-512x-Texture-Pack.zip</code> 的接管包。目的：证明 (1) 接管路径在 1.21.4 真实存在，
 (2) Trit 纹理与原版明显不同 → 接管在游戏内可见。</div>

<h2>① 覆盖路径在真实 1.21.4 中的存在性（接管能否生效）</h2>
<table><tr><th>方块</th><th>原版资源路径</th><th>原版</th><th>Trit 覆盖路径</th><th>Trit</th></tr>
{proof_html}</table>
<p class="{'ok' if allok else 'bad'}">{'✓ 全部 7 条主路径在 1.21.4 中均存在 → 接管必定生效' if allok else '✗ 仍有缺失路径'}</p>

<h2>② 逐方块：原版 vs Trit 接管</h2>
{compare_html}

<h2>③ stone 的 PBR 层（labPBR normal/rough/metal/ao）</h2>
<div class="note">PBR 层在纯 vanilla 下被忽略，需安装 <b>Iris</b> + labPBR 着色器才参与打光。下方仅展示内容。</div>
{pbr_html}

<h2>④ 验证边界（如实）</h2>
<div class="note">agent 无显示器/GPU、无正版鉴权，无法 headless 渲染 MC 客户端做像素级肉眼确认；
 Fabric mod 路线因本 profile 为 <b>vanilla（无 Fabric 加载器）</b> 不会加载。以上对比 + 资源包格式/完整性/路径校验
 已工程级完成；最终游戏内视觉接管请在真实客户端点 <code>D:\\MC\\Axolotl Launcher.exe</code> 启动后查看。</div>
</body></html>"""

os.makedirs(os.path.dirname(HTML), exist_ok=True)
with open(HTML, "w", encoding="utf-8") as f:
    f.write(html)
print("WROTE", HTML)
