#!/usr/bin/env python3
"""
데모 이미지를 Sanity 에셋으로 업로드 → 공개 CDN URL 맵을 만든다.

왜: 코스 media url 을 BE 로컬 정적경로(/images/demo/..)로 두면 FE 가 자기 origin 기준으로 해석해
깨진다. Sanity 에셋은 절대 CDN URL(cdn.sanity.io, public dataset)이라 어디서 띄워도 보인다.

- 토큰: ~/.config/sanity/config.json 의 authToken (sanity CLI 로그인 토큰). 출력/커밋 금지.
- 업로드는 content-hash 기반이라 동일 이미지 재업로드는 같은 에셋(idempotent). 그래도 이미 맵에
  있으면 건너뛴다.
- 결과: scripts/demo_sanity_assets.json = { "lecture-1-1.jpg": "https://cdn.sanity.io/...", ... }
"""
import json
import pathlib
import sys
import urllib.request
import urllib.error

ROOT = pathlib.Path(__file__).resolve().parent.parent
IMG_DIR = ROOT / "src/main/resources/static/images/demo"
OUT = ROOT / "scripts/demo_sanity_assets.json"
PROJECT, DATASET = "rc448mwo", "production"
ENDPOINT = f"https://{PROJECT}.api.sanity.io/v2021-06-07/assets/images/{DATASET}"


def token():
    cfg = json.load(open(pathlib.Path.home() / ".config/sanity/config.json"))
    t = cfg.get("authToken")
    if not t:
        sys.exit("Sanity authToken 없음 — `npx sanity login` 먼저.")
    return t


def upload(tok, path):
    req = urllib.request.Request(
        f"{ENDPOINT}?filename={path.name}", data=path.read_bytes(), method="POST")
    req.add_header("Authorization", "Bearer " + tok)
    req.add_header("Content-Type", "image/jpeg")
    with urllib.request.urlopen(req) as r:
        return json.load(r)["document"]["url"]


def main():
    tok = token()
    existing = json.loads(OUT.read_text()) if OUT.exists() else {}
    files = sorted(IMG_DIR.glob("*.jpg"))
    if not files:
        sys.exit(f"이미지 없음: {IMG_DIR}")
    for p in files:
        if p.name in existing:
            print(f"  skip {p.name} (이미 업로드됨)")
            continue
        try:
            existing[p.name] = upload(tok, p)
            print(f"  ↑ {p.name}")
        except urllib.error.HTTPError as e:
            sys.exit(f"업로드 실패 {p.name}: {e.code} {e.read().decode()[:300]}")
    OUT.write_text(json.dumps(existing, ensure_ascii=False, indent=2))
    print(f"완료 — {len(existing)}개 에셋 맵 저장: {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
