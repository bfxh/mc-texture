#!/usr/bin/env python3
"""手工拉起 1.21.4 + Fabric（绕开 Axolotl 残缺的 fabric 版本 json）。

用途：验证 trit-texture mod 在真实 Fabric 环境下不崩、且能被加载。
不修改任何 vanilla / 启动器配置。
"""
import json, os, subprocess, sys, time

MC = r"E:\MC\MC"
META = os.path.join(MC, "meta")
VER = "1.21.4"
VJSON = os.path.join(META, "versions", VER, VER + ".json")
GAMEDIR = os.path.join(MC, "profiles", "1.21.4-Fabric")
JAVA = r"C:\Program Files\Java\jdk-21.0.12.1\bin\javaw.exe"

ASM = [os.path.join(META, "libraries", "org", "ow2", "asm", m, "9.10.1", "%s-9.10.1.jar" % m)
       for m in ("asm", "asm-analysis", "asm-commons", "asm-tree", "asm-util")]

LOADER = os.path.join(META, "libraries", "net", "fabricmc", "fabric-loader", "0.20.1",
                      "fabric-loader-0.20.1.jar")
MIXIN = os.path.join(META, "libraries", "net", "fabricmc", "sponge-mixin",
                     "0.17.5+mixin.0.8.7", "sponge-mixin-0.17.5+mixin.0.8.7.jar")
INTERMEDIARY = os.path.join(META, "libraries", "net", "fabricmc", "intermediary", VER,
                            "intermediary-%s-v2.jar" % VER)


def main():
    d = json.load(open(VJSON, encoding="utf-8"))

    cp = []
    for l in d["libraries"]:
        a = l.get("downloads", {}).get("artifact")
        if not a or not a.get("path"):
            continue
        # 只收 Windows 相关：无 rules 或有 windows allow
        rules = l.get("rules")
        if rules:
            win = False
            for r in rules:
                os_ = r.get("os", {})
                act = r.get("action")
                if os_.get("name") in ("windows", "windows-x86", "windows-arm64") and act == "allow":
                    win = True
            if not win:
                continue
        p = os.path.join(META, "libraries", a["path"].replace("/", os.sep))
        if os.path.exists(p):
            cp.append(p)

    gamejar = os.path.join(META, "versions", VER, VER + ".jar")
    # MC 1.21.4 自带 asm 9.6（仅 asm 核心），Fabric loader 0.20.1 需要 asm-tree 等且
    # 禁止 classpath 上出现重复 ASM 类 —— 故剔除 MC 的 9.6，统一用 9.10.1 一套。
    cp = [p for p in cp if not p.replace("\\", "/").endswith("org/ow2/asm/asm/9.6/asm-9.6.jar")]
    cp = [gamejar, LOADER, MIXIN, INTERMEDIARY] + ASM + cp
    for p in [LOADER, MIXIN, INTERMEDIARY] + ASM:
        if not os.path.exists(p):
            print("MISSING", p); sys.exit(2)

    cpstr = os.pathsep.join(cp)
    natives = os.path.join(META, "natives", VER)
    assets = os.path.join(META, "assets")
    logcfg = None
    for f in os.listdir(os.path.join(META, "log_configs")):
        if f.startswith("client-1.12"):
            logcfg = os.path.join(META, "log_configs", f)
    print("logcfg", logcfg)

    sub = {
        "${natives_directory}": natives,
        "${launcher_name}": "axolotl-manual",
        "${launcher_version}": "0.0",
        "${classpath}": cpstr,
        "${library_directory}": os.path.join(META, "libraries"),
        "${classpath_separator}": os.pathsep,
        "${version_name}": VER,
        "${game_directory}": GAMEDIR,
        "${assets_root}": assets,
        "${assets_index_name}": d["assetIndex"]["id"],
        "${user_type}": "msa",
        "${version_type}": d.get("type", "release"),
        "${resolution_width}": "1280",
        "${resolution_height}": "720",
        "${user_properties}": "{}",
        "${clientid}": "0",
        "${auth_access_token}": "0",
        "${auth_session}": "0",
        "${auth_player_name}": "TritTester",
        "${auth_uuid}": "00000000-0000-0000-0000-000000000000",
        "${auth_xuid}": "0",
        "${quickPlayPath}": "",
        "${quickPlaySingleplayer}": "",
        "${quickPlayMultiplayer}": "",
        "${quickPlayRealms}": "",
    }

    def resolve(v):
        if isinstance(v, list):
            out = []
            for x in v:
                if isinstance(x, str) and x.startswith("-Dlog4j.configurationFile="):
                    continue
                out.append(resolve(x))
            return out
        if isinstance(v, str):
            for k, r in sub.items():
                v = v.replace(k, r)
            return v
        return v

    args = d.get("arguments", {})
    jvm = []
    for x in args.get("jvm", []):
        if isinstance(x, dict):
            rules = x.get("rules", [])
            ok = True
            for r in rules:
                os_ = r.get("os", {})
                if r.get("action") == "allow" and os_.get("name") not in ("windows",):
                    ok = False
            if not ok:
                continue
            v = x.get("value", [])
            if isinstance(v, str):
                jvm.append(resolve(v))
            else:
                jvm += resolve(v)
        else:
            jvm.append(resolve(x))
    jvm = [a for a in jvm if isinstance(a, str)]
    if logcfg:
        jvm.append("-Dlog4j.configurationFile=" + logcfg)
    jvm += ["-Dfabric.dli.config=" + os.path.join(GAMEDIR, ".fabric", "config.json")]

    game = []
    for x in args.get("game", []):
        game.append(resolve(x) if isinstance(x, str) else x)
    # 离线：把 --server/--port 之类去掉
    game = [g for g in game if isinstance(g, str)]

    cmd = [JAVA] + jvm + ["net.fabricmc.loader.impl.launch.knot.KnotClient"] + game
    print("CP entries:", len(cp))
    print("CMD len:", len(cmd))
    env = dict(os.environ)
    env["JAVA_HOME"] = r"C:\Program Files\Java\jdk-21.0.12.1"
    logp = os.path.join(GAMEDIR, "manual-launch.log")
    with open(logp, "w", encoding="utf-8", errors="replace") as f:
        p = subprocess.Popen(cmd, cwd=GAMEDIR, stdout=f, stderr=subprocess.STDOUT, env=env)
    print("pid", p.pid)
    open(os.path.join(GAMEDIR, "manual-launch.pid"), "w").write(str(p.pid))


if __name__ == "__main__":
    main()
