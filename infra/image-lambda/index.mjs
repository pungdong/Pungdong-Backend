// 온디맨드 이미지 변환 — CloudFront 의 /r/* behavior origin (Lambda Function URL, OAC).
//
// 요청:  GET https://<cdn>/r/{key}?w=&h=&fm=&q=&fit=
//   - {key}        : 공개 버킷의 객체 키 (예: course/<uuid>.jpg). /r/ prefix 는 제거 후 사용.
//   - w,h          : 목표 px (정수, 1..MAX_DIM). 둘 중 하나만 줘도 비율 유지.
//   - fm           : webp | avif | jpeg | png (기본: 원본 포맷 유지)
//   - q            : 품질 1..100 (기본 80)
//   - fit          : cover | contain | inside | outside | fill (기본 inside — 비율유지·확대안함)
//
// 동작: 공개 버킷에서 원본을 받아 sharp 로 변환해 반환. 변환 파라미터가 없으면 원본 그대로 통과.
// 결과는 CloudFront 가 엣지 캐시하므로 이 Lambda 는 캐시 미스에만 실행된다.
//
// 보안: Function URL = AWS_IAM, CloudFront OAC(SigV4)로만 호출. 버킷은 비공개(BPA on) — Lambda 가
//       실행 역할 IAM 으로 GetObject.

import { S3Client, GetObjectCommand } from '@aws-sdk/client-s3';
import sharp from 'sharp';

const s3 = new S3Client({});
const BUCKET = process.env.PUBLIC_BUCKET;
const MAX_DIM = 4000;                 // 폭주 방지(임의 거대 사이즈 요청 차단)
const ALLOWED_FM = new Set(['webp', 'avif', 'jpeg', 'jpg', 'png']);
const ALLOWED_FIT = new Set(['cover', 'contain', 'inside', 'outside', 'fill']);
const LONG_CACHE = 'public, max-age=31536000, immutable'; // 키 불변이라 영구 캐시

function clampInt(v, min, max) {
  const n = parseInt(v, 10);
  if (Number.isNaN(n)) return undefined;
  return Math.max(min, Math.min(max, n));
}

async function streamToBuffer(stream) {
  const chunks = [];
  for await (const chunk of stream) chunks.push(chunk);
  return Buffer.concat(chunks);
}

function resp(statusCode, headers, body, isBase64Encoded = false) {
  return { statusCode, headers, body, isBase64Encoded };
}

export const handler = async (event) => {
  // Lambda Function URL payload v2.0
  const rawPath = event.rawPath || '/';
  const q = event.queryStringParameters || {};

  // /r/{key} → {key}. (CloudFront behavior path_pattern = /r/* 로 라우팅됨.)
  let key = decodeURIComponent(rawPath.replace(/^\/+/, '')); // 앞쪽 슬래시 제거
  if (key.startsWith('r/')) key = key.slice(2);
  if (!key) return resp(400, { 'content-type': 'text/plain' }, 'missing key');

  // 원본 로드
  let original, contentType;
  try {
    const obj = await s3.send(new GetObjectCommand({ Bucket: BUCKET, Key: key }));
    original = await streamToBuffer(obj.Body);
    contentType = obj.ContentType;
  } catch (e) {
    if (e.name === 'NoSuchKey' || e.$metadata?.httpStatusCode === 404) {
      return resp(404, { 'content-type': 'text/plain' }, 'not found');
    }
    console.error('s3 get failed', key, e);
    return resp(502, { 'content-type': 'text/plain' }, 'origin error');
  }

  const width = clampInt(q.w, 1, MAX_DIM);
  const height = clampInt(q.h, 1, MAX_DIM);
  const fm = ALLOWED_FM.has((q.fm || '').toLowerCase()) ? q.fm.toLowerCase() : undefined;
  const quality = clampInt(q.q, 1, 100) ?? 80;
  const fit = ALLOWED_FIT.has((q.fit || '').toLowerCase()) ? q.fit.toLowerCase() : 'inside';

  // 변환 파라미터가 전혀 없으면 원본 통과 (Lambda 가 단순 프록시).
  if (!width && !height && !fm) {
    return resp(200, {
      'content-type': contentType || 'application/octet-stream',
      'cache-control': LONG_CACHE,
    }, original.toString('base64'), true);
  }

  try {
    let img = sharp(original, { failOn: 'none' }).rotate(); // EXIF 회전 반영
    if (width || height) {
      img = img.resize({ width, height, fit, withoutEnlargement: true });
    }
    let outType = contentType || 'application/octet-stream';
    if (fm) {
      const f = fm === 'jpg' ? 'jpeg' : fm;
      img = img.toFormat(f, { quality });
      outType = `image/${f}`;
    } else if (quality && /jpe?g|webp|avif/.test(outType)) {
      // 포맷 유지 + 품질만: 원본 포맷에 quality 적용
      const f = outType.includes('webp') ? 'webp' : outType.includes('avif') ? 'avif' : 'jpeg';
      img = img.toFormat(f, { quality });
    }
    const out = await img.toBuffer();
    return resp(200, { 'content-type': outType, 'cache-control': LONG_CACHE }, out.toString('base64'), true);
  } catch (e) {
    console.error('transform failed', key, e);
    return resp(500, { 'content-type': 'text/plain' }, 'transform error');
  }
};
