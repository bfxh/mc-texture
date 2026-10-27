#!/usr/bin/env python3
"""直接拉起 Axolotl 的某个 Fabric 实例（不依赖 Axolotl 的残缺版本 json）。

用法: python launch.py <版本目录名> <profile 目录名>
例  : python launch.py 26.3-0.20.1 "26.3-Fabric 0.20.1"
"""
import glob
import json
import os
import subprocess
import sys

MC = r"E:\MC\MC"
META = os.path.join(MC, "meta")
JAVA = r"E:\MC\MC\meta\java_versions\mojang-java-runtime-epsilon-windows-x64-b374544c680d\bin\javaw.exe"


def main():
    ver = sys.argv[1]
    prof = sys.argv[2]
    vdir = os.path.join(META, "versions", ver)
    vjson = glob.glob(os.path.join(vdir, "*.json"))[0]
    gamedir = os.path.join(MC, "profiles", prof)
    d = json.load(open(vjson, encoding="utf-8"))

    def maven_path(name):
        # group:artifact:version[:classifier] -> group/artifact/version/artifact-version[-classifier].jar
        parts = name.split(":")
        if len(parts) < 3:
            return None
        g, art, ver = parts[0], parts[1], parts[2]
        cls = parts[3] if len(parts) > 3 else None
        fname = "%s-%s%s.jar" % (art, ver, ("-" + cls) if cls else "")
        return os.path.join(*(g.split(".") + [art, ver, fname]))

    cp = []
    for l in d["libraries"]:
        a = l.get("downloads", {}).get("artifact")
        if not a or not a.get("path"):
            # Axolotl 自己写的 fabric 库条目没有 downloads 字段，按 maven 坐标推导路径
            p = maven_path(l.get("name", ""))
        else:
            p = a["path"].replace("/", os.sep)
        if not p:
            continue
        rules = l.get("rules")
        if rules and not any(
            r.get("action") == "allow" and r.get("os", {}).get("name") in
            ("windows", "windows-x86", "windows-arm64") for r in rules):
            continue
        full = os.path.join(META, "libraries", p)
        if os.path.exists(full):
            cp.append(full)

    cp.insert(0, os.path.join(vdir, ver + ".jar"))

    natives = os.path.join(META, "natives", ver)
    if not os.path.isdir(natives):
        natives = os.path.join(META, "natives", d["id"])
    logcfg = None
    lc = os.path.join(META, "log_configs")
    if os.path.isdir(lc):
        for f in sorted(os.listdir(lc)):
            if f.startswith("client-1.21"):
                logcfg = os.path.join(lc, f)

    sub = {
        "${natives_directory}": natives,
        "${launcher_name}": "trit-manual",
        "${launcher_version}": "0.0",
        "${classpath}": os.pathsep.join(cp),
        "${library_directory}": os.path.join(META, "libraries"),
        "${classpath_separator}": os.pathsep,
        "${version_name}": d["id"],
        "${game_directory}": gamedir,
        "${assets_root}": os.path.join(META, "assets"),
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

    def res(v):
        if isinstance(v, list):
            return [res(x) for x in v]
        if isinstance(v, str):
            for k, r in sub.items():
                v = v.replace(k, r)
            return v
        return v

    jvm = []
    for x in d.get("arguments", {}).get("jvm", []):
        if isinstance(x, dict):
            rules = x.get("rules", [])
            ok = all(r.get("action") != "allow" or r.get("os", {}).get("name") == "windows" for r in rules)
            if not ok:
                continue
            v = x.get("value", [])
            jvm += [res(v)] if isinstance(v, str) else res(v)
        else:
            jvm.append(res(x))
    jvm = [a for a in jvm if isinstance(a, str) and a]
    if logcfg:
        jvm = [a for a in jvm if not a.startswith("-Dlog4j.configurationFile=")]
        jvm.append("-Dlog4j.configurationFile=" + logcfg)

    game = [res(x) for x in d.get("arguments", {}).get("game", []) if isinstance(x, str)]

    cmd = [JAVA] + jvm + [d["mainClass"]] + game
    logp = os.path.join(gamedir, "manual-launch.log")
    with open(logp, "w", encoding="utf-8", errors="replace") as f:
        p = subprocess.Popen(cmd, cwd=gamedir, stdout=f, stderr=subprocess.STDOUT)
    print("pid", p.pid, "->", logp)
    open(os.path.join(gamedir, "manual-launch.pid"), "w").write(str(p.pid))


if __name__ == "__main__":
    main()
