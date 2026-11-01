#!/usr/bin/env python3
"""Builds every downloadable model pack from upstream weights and writes the manifest.

    python build_packs.py --work /tmp/packwork --out dist \
        --base-url https://github.com/EmotionalLeaves/Rescale/releases/download/models-v1

Outputs dist/rescale-pack-<id>.zip for each pack and dist/packs.json (zip size + SHA-256 and
per-file SHA-256s), which the app bundles as assets/models/packs.json. Used by
.github/workflows/models.yml; runs locally too (needs torch, torchvision, pnnx, ncnn).
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
RESR = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/"
SOURCES = {
    "general-x4v3.pth": RESR + "realesr-general-x4v3.pth",
    "general-wdn-x4v3.pth": RESR + "realesr-general-wdn-x4v3.pth",
    "realesrgan-ncnn-vulkan.zip": RESR + "realesrgan-ncnn-vulkan-20220424-ubuntu.zip",
    "fbcnn_color.pth": "https://github.com/jiaxi-jiang/FBCNN/releases/download/v1.0/fbcnn_color.pth",
    "GFPGANv1.4.pth": "https://github.com/TencentARC/GFPGAN/releases/download/v1.3.4/GFPGANv1.4.pth",
    "retinaface_mnet.pth": "https://github.com/xinntao/facexlib/releases/download/v0.1.0/detection_mobilenet0.25_Final.pth",
}
REPOS = {
    "FBCNN": "https://github.com/jiaxi-jiang/FBCNN",
    "GFPGAN": "https://github.com/TencentARC/GFPGAN",
    "facexlib": "https://github.com/xinntao/facexlib",
}

BSD_RESR = "Real-ESRGAN models: BSD-3-Clause, (c) 2021 Xintao Wang. https://github.com/xinntao/Real-ESRGAN\n"
APACHE_FBCNN = "FBCNN: Apache-2.0, (c) Jiaxi Jiang et al. https://github.com/jiaxi-jiang/FBCNN\n"
FACES_LIC = ("GFPGAN v1.4: Apache-2.0, (c) 2021 THL A29 Limited (Tencent). https://github.com/TencentARC/GFPGAN\n"
             "Converted from the 'clean' architecture (no StyleGAN2 CUDA ops).\n"
             "RetinaFace MobileNet-0.25 (facexlib): MIT, (c) 2020 Xintao Wang. https://github.com/xinntao/facexlib\n")


def fetch(work):
    os.makedirs(work, exist_ok=True)
    for name, url in SOURCES.items():
        path = os.path.join(work, name)
        if not os.path.exists(path):
            print("downloading", url, flush=True)
            urllib.request.urlretrieve(url, path + ".tmp")
            os.replace(path + ".tmp", path)
    resr = os.path.join(work, "resr")
    if not os.path.isdir(resr):
        with zipfile.ZipFile(os.path.join(work, "realesrgan-ncnn-vulkan.zip")) as z:
            z.extractall(resr)
    for name, url in REPOS.items():
        d = os.path.join(work, name)
        if not os.path.isdir(d):
            subprocess.run(["git", "clone", "--depth", "1", url, d], check=True)
    return resr, os.path.join(work, "FBCNN")


def run(*args):
    print("+", " ".join(args), flush=True)
    subprocess.run([sys.executable, *args], check=True, cwd=HERE)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def deterministic_zip(path, files, license_text):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for src in sorted(files, key=os.path.basename):
            info = zipfile.ZipInfo(os.path.basename(src), date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with open(src, "rb") as f:
                z.writestr(info, f.read())
        info = zipfile.ZipInfo("LICENSE.txt", date_time=(2020, 1, 1, 0, 0, 0))
        z.writestr(info, license_text)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--only", help="comma-separated pack ids to build (default: all)")
    ap.add_argument("--merge", help="existing manifest: keep its entries for packs not rebuilt")
    args = ap.parse_args()
    only = set(args.only.split(",")) if args.only else None

    work = os.path.abspath(args.work)
    out = os.path.abspath(args.out)
    models = os.path.join(work, "models")
    os.makedirs(models, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    resr, fb = fetch(work)
    w = lambda n: os.path.join(work, n)  # noqa: E731
    m = lambda n: os.path.join(models, n)  # noqa: E731

    want = lambda pid: only is None or pid in only  # noqa: E731
    if want("denoise"):  # x4v3 blended with its wdn twin (network interpolation)
        for pct in (50, 100):
            run("export_ncnn.py", "--weights", w("general-x4v3.pth"), "--wdn", w("general-wdn-x4v3.pth"),
                "--denoise", str(pct / 100), "--out", m(f"realesr-general-dn{pct}"))
    if want("jpeg"):
        run("export_fbcnn.py", "--repo", fb, "--weights", w("fbcnn_color.pth"), "--out", m("fbcnn-color"))
    if want("photo-pro") or want("anime-pro"):  # as published by Real-ESRGAN
        for name in ("realesrgan-x4plus", "realesrgan-x4plus-anime"):
            for ext in (".param", ".bin"):
                shutil.copy(os.path.join(resr, "models", name + ext), m(name + ext))
    if want("faces"):
        run("export_gfpgan.py", "--repo", w("GFPGAN"), "--weights", w("GFPGANv1.4.pth"), "--out", m("gfpgan-v1.4"))
        run("export_retinaface.py", "--repo", w("facexlib"), "--weights", w("retinaface_mnet.pth"),
            "--out", m("retinaface-mnet"))

    packs = {
        "jpeg": (["fbcnn-color"], APACHE_FBCNN),
        "denoise": (["realesr-general-dn50", "realesr-general-dn100"], BSD_RESR),
        "photo-pro": (["realesrgan-x4plus"], BSD_RESR),
        "anime-pro": (["realesrgan-x4plus-anime"], BSD_RESR),
        "faces": (["gfpgan-v1.4", "retinaface-mnet"], FACES_LIC),
    }
    manifest = {"version": 1, "packs": []}
    if args.merge and os.path.exists(args.merge):
        old = json.load(open(args.merge))
        manifest["packs"] = [p for p in old.get("packs", []) if only is not None and p["id"] not in only]
    for pack_id, (names, lic) in packs.items():
        if not want(pack_id):
            continue
        files = [m(n + ext) for n in names for ext in (".param", ".bin")]
        zpath = os.path.join(out, f"rescale-pack-{pack_id}.zip")
        deterministic_zip(zpath, files, lic)
        manifest["packs"].append({
            "id": pack_id,
            "url": f"{args.base_url.rstrip('/')}/rescale-pack-{pack_id}.zip",
            "size": os.path.getsize(zpath),
            "sha256": sha256(zpath),
            "files": {os.path.basename(f): sha256(f) for f in sorted(files)},
        })
        print(f"{pack_id}: {os.path.getsize(zpath) >> 20} MB", flush=True)
    with open(os.path.join(out, "packs.json"), "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
