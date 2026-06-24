#!/usr/bin/env bash
# OCEAN 투어 3곳 공개 사진 다운로드(주로 Wikimedia Commons CC + Flickr). content-type 검증.
set -u
cd "$(dirname "$0")"
UA='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'
OUT=images
dl() {
  local venue="$1" referer="$2"; shift 2
  local dir="$OUT/$venue"; mkdir -p "$dir"; local i=1
  for url in "$@"; do
    local ext="${url##*.}"; ext="${ext%%\?*}"; ext="$(echo "$ext" | tr '[:upper:]' '[:lower:]')"
    case "$ext" in jpg|jpeg|png|webp) : ;; *) ext=jpg ;; esac
    local f="$dir/$(printf '%02d' "$i").$ext"
    if curl -fsSL --max-time 40 -A "$UA" -e "$referer" -o "$f" "$url"; then
      local ct; ct=$(file --mime-type -b "$f" 2>/dev/null)
      if [[ "$ct" == image/* ]]; then echo "OK   $venue  $f  ($ct, $(du -h "$f" | cut -f1))"
      else echo "BAD  $venue  $url -> $ct, rm"; rm -f "$f"; fi
    else echo "FAIL $venue  $url"; fi
    i=$((i+1))
  done
}

dl venue-jeju-seogwipo "https://www.flickr.com/" \
  "https://live.staticflickr.com/643/21748725356_66f06d8b45_b.jpg" \
  "https://live.staticflickr.com/675/21625801328_9289b751fe_b.jpg" \
  "https://upload.wikimedia.org/wikipedia/commons/0/09/Squid_Boats_in_the_Harbor.jpg"

dl venue-ulleungdo "https://commons.wikimedia.org/" \
  "https://upload.wikimedia.org/wikipedia/commons/d/d7/Jukdo_island.jpg" \
  "https://upload.wikimedia.org/wikipedia/commons/d/db/%EC%9A%B8%EB%A6%89%EB%8F%84%26%EC%A3%BD%EB%8F%84%26%EC%84%9D%EB%8F%84.jpg" \
  "https://upload.wikimedia.org/wikipedia/commons/d/d5/Ousan%28Jukdo%29.jpg"

dl venue-dokdo "https://commons.wikimedia.org/" \
  "https://upload.wikimedia.org/wikipedia/commons/c/c4/Dokdo_in_2018.jpg" \
  "https://upload.wikimedia.org/wikipedia/commons/5/5e/Dokdo_Photo.jpg" \
  "https://upload.wikimedia.org/wikipedia/commons/6/6a/Dokdo_20080628-panorama.jpg"

echo "=== done ==="
