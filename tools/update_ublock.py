#!/usr/bin/env python3
"""Tải uBlock Origin (bản Firefox) và vá cấu hình mặc định cho Phim275.

Cách dùng:
    python tools/update_ublock.py            # dùng phiên bản ghi trong UBO_VERSION
    python tools/update_ublock.py 1.76.0     # nâng lên phiên bản khác

uBlock Origin © Raymond Hill và cộng sự, giấy phép GPLv3.
Mã nguồn gốc: https://github.com/gorhill/uBlock
"""
from __future__ import annotations

import io
import json
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEST = ROOT / "app" / "src" / "main" / "assets" / "extensions" / "ublock"
VERSION_FILE = ROOT / "tools" / "UBO_VERSION"

# Các bộ lọc bật sẵn ngoài mặc định của uBO.
# Giữ danh sách gọn: máy chiếu/TV box chỉ có ~2GB RAM, mỗi bộ lọc tốn thêm bộ nhớ.
EXTRA_ENABLED_LISTS = [
    "VIE-1",                   # ABPVN — quảng cáo trên trang Việt Nam
    # Không bật "adguard-generic": trên Android uBO tự bật "adguard-mobile" (nhẹ hơn,
    # cùng nguồn AdGuard) — bật cả hai thì trùng lặp và tốn RAM.
    "adguard-popup-overlays",  # AdGuard Popup Overlays — lớp phủ che nội dung
    "ublock-annoyances",       # uBlock Annoyances — khung phiền toái, anti-adblock nhẹ
]

# Bộ lọc mặc định của uBO nhưng tắt đi để tiết kiệm RAM trên TV.
DISABLED_LISTS = [
    "urlhaus-1",  # ~110k luật / 12MB, chỉ chặn URL mã độc (không phải quảng cáo)
]

# Danh sách lọc riêng của Phim275: bản trong repo được đóng gói làm bản dự phòng,
# uBO tự cập nhật từ GitHub (repo công khai) mỗi ngày.
PHIM275_LIST_ID = "phim275-filters"
PHIM275_LIST_SRC = ROOT / "filters" / "phim275.txt"
PHIM275_LIST_LOCAL = "assets/phim275/phim275.txt"
PHIM275_LIST_REMOTE = "https://raw.githubusercontent.com/williamsvo2705/phim275/main/filters/phim275.txt"

# Bản sao bộ lọc đóng gói sẵn không còn dùng (đã tắt) — xóa cho APK nhẹ.
# Nếu người dùng tự bật lại, uBO sẽ tải bản mới từ mạng.
REMOVE_BUNDLED = [
    "assets/thirdparties/urlhaus-filter",
]


def download_xpi(version: str) -> bytes:
    url = (
        f"https://github.com/gorhill/uBlock/releases/download/"
        f"{version}/uBlock0_{version}.firefox.signed.xpi"
    )
    print(f"Tải {url}")
    with urllib.request.urlopen(url, timeout=120) as resp:
        return resp.read()


def extract(xpi: bytes) -> None:
    DEST.mkdir(parents=True, exist_ok=True)
    # Xóa nội dung cũ (không xóa chính thư mục — trên Windows có thể đang bị process khác giữ).
    for child in DEST.iterdir():
        if child.is_dir():
            shutil.rmtree(child)
        else:
            child.unlink()
    with zipfile.ZipFile(io.BytesIO(xpi)) as zf:
        zf.extractall(DEST)
    # Chữ ký Mozilla không còn đúng sau khi vá; extension tích hợp sẵn không cần chữ ký.
    shutil.rmtree(DEST / "META-INF", ignore_errors=True)


def configure_lists(enable: list[str], disable: list[str]) -> None:
    assets_json = DEST / "assets" / "assets.json"
    data = json.loads(assets_json.read_text(encoding="utf-8"))
    missing = [lid for lid in enable + disable if lid not in data]
    if missing:
        raise SystemExit(f"Không tìm thấy bộ lọc trong assets.json: {missing}")
    for lid in enable:
        data[lid].pop("off", None)
        print(f"  bật sẵn: {lid} — {data[lid].get('title')}")
    for lid in disable:
        data[lid]["off"] = True
        print(f"  tắt    : {lid} — {data[lid].get('title')}")

    # Không cho uBO tải assets.json từ máy chủ của uBO: khi tải về, uBO đồng bộ lại
    # "danh sách mặc định" theo bản gốc → tắt ABPVN, bật lại URLhaus (đã kiểm chứng
    # trên emulator). Các bộ lọc vẫn tự cập nhật nội dung như thường; danh mục bộ lọc
    # được làm mới mỗi khi Phim275 nâng phiên bản uBO.
    data["assets.json"]["contentURL"] = ["assets/assets.json"]
    data["assets.json"].pop("cdnURLs", None)
    print("  khóa assets.json: chỉ dùng bản đóng gói")

    # Thêm danh sách lọc riêng của Phim275 (bật sẵn, nhóm mặc định).
    data[PHIM275_LIST_ID] = {
        "content": "filters",
        "group": "default",
        "title": "Phim275 – Bộ lọc riêng",
        "tags": "ads annoyances",
        "contentURL": [PHIM275_LIST_REMOTE, PHIM275_LIST_LOCAL],
        "supportURL": "https://github.com/williamsvo2705/phim275/issues",
    }
    print(f"  thêm   : {PHIM275_LIST_ID}")
    assets_json.write_text(
        json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8"
    )


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")  # console Windows mặc định cp1252
    version = sys.argv[1] if len(sys.argv) > 1 else VERSION_FILE.read_text().strip()
    extract(download_xpi(version))
    configure_lists(EXTRA_ENABLED_LISTS, DISABLED_LISTS)
    local_copy = DEST / PHIM275_LIST_LOCAL
    local_copy.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(PHIM275_LIST_SRC, local_copy)
    for rel in REMOVE_BUNDLED:
        shutil.rmtree(DEST / rel, ignore_errors=True)
        print(f"  xóa bản đóng gói: {rel}")
    VERSION_FILE.write_text(version + "\n")
    manifest = json.loads((DEST / "manifest.json").read_text(encoding="utf-8"))
    print(f"Xong: uBlock Origin {manifest['version']} → {DEST.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
