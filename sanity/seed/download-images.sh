#!/usr/bin/env bash
# venues-extra 용 공개 사진 다운로드. 각 URL 을 브라우저 UA + 적절한 Referer 로 받아
# seed/images/<venue>/ 에 저장하고 content-type 이 image 인지 검증. 실패분은 로그만 남기고 스킵.
set -u
cd "$(dirname "$0")"
UA='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'
OUT=images
mkdir -p "$OUT"

# venue|referer|url1 url2 url3 ...
dl() {
  local venue="$1" referer="$2"; shift 2
  local dir="$OUT/$venue"; mkdir -p "$dir"
  local i=1
  for url in "$@"; do
    local ext="${url##*.}"; ext="${ext%%\?*}"; ext="$(echo "$ext" | tr '[:upper:]' '[:lower:]')"
    case "$ext" in jpg|jpeg|png|webp) : ;; *) ext=jpg ;; esac
    local f="$dir/$(printf '%02d' "$i").$ext"
    if curl -fsSL --max-time 40 -A "$UA" -e "$referer" -o "$f" "$url"; then
      local ct; ct=$(file --mime-type -b "$f" 2>/dev/null)
      if [[ "$ct" == image/* ]]; then
        echo "OK   $venue  $f  ($ct, $(du -h "$f" | cut -f1))"
      else
        echo "BAD  $venue  $url -> not image ($ct), removing"; rm -f "$f"
      fi
    else
      echo "FAIL $venue  $url"
    fi
    i=$((i+1))
  done
}

dl venue-busan-bukhang "https://www.kookje.co.kr/" \
  "https://db.kookje.co.kr/news2000/photo/2024/0222/L20240222.22012005221i1.jpg" \
  "https://db.kookje.co.kr/news2000/photo/2024/0222/L20240222.22012005221i7.jpg" \
  "https://db.kookje.co.kr/news2000/photo/2024/0222/L20240222.22012005221i6.jpg"

dl venue-changwon "https://www.cwsisul.or.kr/_chinswimr/_sub01/sub01_06_01.html" \
  "https://www.cwsisul.or.kr/_chinswimr/_sub01/img/01_06_04.jpg" \
  "https://www.cwsisul.or.kr/_chinswimr/_sub01/img/01_06_02.jpg" \
  "https://www.cwsisul.or.kr/_chinswimr/_sub01/img/01_06_03.jpg"

dl venue-songdo "https://kr.trip.com/" \
  "https://ak-d.tripcdn.com/images/1mi6m224x8vhzskay6E78_W_640_0_R5_Q80.jpg" \
  "https://ak-d.tripcdn.com/images/1mi0p224x8vhzreyl350E_W_640_0_R5_Q80.jpg" \
  "https://ak-d.tripcdn.com/images/1mi6a224x8vhzq5ee9C98_W_640_0_R5_Q80.jpg"

dl venue-sujak-goyang "https://korean.visitkorea.or.kr/" \
  "http://tong.visitkorea.or.kr/cms/resource/41/2735241_image2_1.jpg" \
  "http://tong.visitkorea.or.kr/cms/resource/42/2735242_image2_1.jpg" \
  "http://tong.visitkorea.or.kr/cms/resource/45/2735245_image2_1.png"

dl venue-paradive35 "https://paradive.co.kr/" \
  "https://paradive.co.kr/data/fac/1750058805_1.jpg" \
  "https://paradive.co.kr/data/fac/1750058813_1.jpg" \
  "https://paradive.co.kr/data/fac/1750058821_1.jpg"

dl venue-alps-daejeon "https://alpsdiving.co.kr/" \
  "https://alpsdiving.co.kr/data/file/preview02/f8937580e2acfa159a0a3fa938d98aa2_cMXrLTAw_32b1d5e05c16f7efb16fbf35c09d46675bc6a63f.JPG" \
  "https://alpsdiving.co.kr/data/file/preview02/f8937580e2acfa159a0a3fa938d98aa2_dObWG2Fo_357d275b36e513bf8f42b9df25071c03d2430479.JPG" \
  "https://alpsdiving.co.kr/data/file/preview01/4_copy_16_f8937580e2acfa159a0a3fa938d98aa2_I4teEbSW_ff6ac9fc4a7046b13ba07997465b95cf1d7eee26.png"

dl venue-duryu-daegu "https://korean.visitkorea.or.kr/" \
  "https://tong.visitkorea.or.kr/cms/resource/91/3516691_image2_1.jpg"

dl venue-nambu-gwangju "https://dh.aks.ac.kr/" \
  "https://dh.aks.ac.kr/~metaArchive/gwangju/2023/%EB%82%A8%EB%B6%80%EB%8C%80%ED%95%99%EA%B5%90%EC%8B%9C%EB%A6%BD%EA%B5%AD%EC%A0%9C%EC%88%98%EC%98%81%EC%9E%A5/%EB%82%A8%EB%B6%80%EB%8C%80%ED%95%99%EA%B5%90%EC%8B%9C%EB%A6%BD%EA%B5%AD%EC%A0%9C%EC%88%98%EC%98%81%EC%9E%A5.jpg" \
  "https://dh.aks.ac.kr/~metaArchive/gwangju/2023/%EB%82%A8%EB%B6%80%EB%8C%80%ED%95%99%EA%B5%90%EC%8B%9C%EB%A6%BD%EA%B5%AD%EC%A0%9C%EC%88%98%EC%98%81%EC%9E%A5/%EB%82%A8%EB%B6%80%EB%8C%80%ED%95%99%EA%B5%90%EC%8B%9C%EB%A6%BD%EA%B5%AD%EC%A0%9C%EC%88%98%EC%98%81%EC%9E%A5_%EC%A0%84%EA%B2%BD1.jpg" \
  "https://dh.aks.ac.kr/~metaArchive/gwangju/2023/%EB%82%A8%EB%B6%80%EB%8C%80%ED%95%99%EA%B5%90%EC%8B%9C%EB%A6%BD%EA%B5%AD%EC%A0%9C%EC%88%98%EC%98%81%EC%9E%A5/%EB%82%A8%EB%B6%80%EB%8C%80%ED%95%99%EA%B5%90%EC%8B%9C%EB%A6%BD%EA%B5%AD%EC%A0%9C%EC%88%98%EC%98%81%EC%9E%A5_%EC%A0%84%EA%B2%BD2.jpg"

dl venue-tsn-osan "https://divingholic.com/" \
  "https://divingholic.com/wp-content/uploads/2023/08/KakaoTalk_20230808_161355239_07-2-560x420.jpg" \
  "https://divingholic.com/wp-content/uploads/2023/08/KakaoTalk_20230808_161355239_08-2-560x420.jpg" \
  "https://divingholic.com/wp-content/uploads/2023/08/KakaoTalk_20230808_161207626_06-2-888x420.jpg"

dl venue-newseoul-gwangmyeong "https://korean.visitkorea.or.kr/" \
  "http://tong.visitkorea.or.kr/cms/resource/04/1852804_image2_1.jpg" \
  "http://tong.visitkorea.or.kr/cms/resource/12/1852812_image2_1.jpg" \
  "http://tong.visitkorea.or.kr/cms/resource/18/1852818_image2_1.jpg"

dl venue-divelife-seoul "https://www.padi.com/" \
  "https://d2p1cf6997m1ir.cloudfront.net/media/thumbnails/e7/a1/e7a10e7e6f128865860922ecccf8c327.webp" \
  "https://d2p1cf6997m1ir.cloudfront.net/media/thumbnails/79/2b/792b17659111c1ed06eeaf1c97d26f97.webp" \
  "https://d2p1cf6997m1ir.cloudfront.net/media/thumbnails/c9/94/c9941d496a6f2f182dd48121f435009c.webp"

dl venue-mer-goyang "https://merdive.co.kr/" \
  "https://merdive.co.kr/web/image/6112-92914a4f/KMK02858.webp" \
  "https://merdive.co.kr/web/image/17603-cf10fa87/KMK07761.webp" \
  "https://merdive.co.kr/web/image/6113-f704c3e4/KakaoTalk_20210114_130940547_05.webp"

echo "=== done ==="
