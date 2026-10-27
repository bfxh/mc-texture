"""Replace the builtin resource pack's pack.mcmeta inside the built mod jar.

Only the pack.mcmeta resource changed (pack_format 34 -> 35); no Java sources
changed, so patching the jar entry is equivalent to a gradle rebuild.
"""
import shutil
import sys
import zipfile

SRC = "D:/KF/.yanagent/mc-texture/trit-mc-mod/fabric/resources/resourcepacks/trit_textures/pack.mcmeta"
TARGETS = [
    "D:/KF/.yanagent/mc-texture/trit-mc-mod/build/libs/trit-texture-0.1.0.jar",
    "E:/MC/MC/profiles/1.21.4-Fabric/mods/trit-texture-0.1.0.jar",
]
ENTRY = "resourcepacks/trit_textures/pack.mcmeta"

new = open(SRC, "rb").read()
print("new pack.mcmeta:\n" + new.decode("utf-8"))

for jar in TARGETS:
    try:
        with zipfile.ZipFile(jar, "r") as zf:
            names = zf.namelist()
            items = [(n, zf.read(n)) for n in names]
    except Exception as e:
        print("SKIP %s (%s)" % (jar, e))
        continue

    if ENTRY not in names:
        print("MISSING entry in %s" % jar)
        continue

    tmp = jar + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zo:
        for n, data in items:
            zo.writestr(n, new if n == ENTRY else data)
    shutil.move(tmp, jar)

    # verify
    with zipfile.ZipFile(jar, "r") as zf:
        got = zf.read(ENTRY)
        pngs = sum(1 for n in zf.namelist()
                   if n.startswith("resourcepacks/trit_textures/assets/minecraft/textures/block/")
                   and n.endswith(".png"))
        ttx = sum(1 for n in zf.namelist() if n.startswith("ttx/") and n.endswith(".ttx2"))
        bad = zf.testzip()
    print("PATCHED %s -> pack.mcmeta=%s pngs=%d ttx2=%d zipOK=%s"
          % (jar, b'"pack_format": 35' in got, pngs, ttx, bad is None))
