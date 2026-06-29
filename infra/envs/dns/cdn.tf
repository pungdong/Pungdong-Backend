# 공개 이미지 CDN — 공개 버킷(비공개·CloudFront OAC) + CloudFront + 커스텀 도메인(cdn[-staging].plop.cool).
#
# 왜 이 영속(dns) 레이어에 두나:
#   - CloudFront 배포는 생성/삭제가 ~15분으로 느리고, 공개 버킷엔 업로드 이미지(데이터)가 쌓인다.
#     staging up/down(env destroy)에 묶이면 매번 느려지고 이미지가 사라진다 → env churn 과 분리해 persistent.
#   - ACM(CloudFront) 인증서는 us-east-1 필수 + DNS 검증이 cdn 존(여기)을 쓴다 → 같은 state 에 두면 cross-state 불필요.
#
# 결정/근거 전문: backend repo docs/features/image-storage-and-serving.md.
#
# 적용 순서(중요):
#   1) 이 레이어 apply → 버킷·CloudFront·zone 생성, 아래 output 에 nameserver.
#   2) 사람(일회성): Squarespace DNS 에서 cdn / cdn-staging 를 NS 레코드로 그 nameserver 에 위임 (api 때와 동일).
#   3) ACM 검증 자동 통과(DNS 전파 후) → CloudFront 배포 전파(~15분).
#   4) 그 다음 envs/{staging,production} apply → 앱에 CLOUD_AWS_S3_PUBLIC_BUCKET·STORAGE_PUBLIC_BASE_URL 주입.

locals {
  cdn = {
    prod = {
      domain = "cdn.plop.cool"
      bucket = "plop-prod-public"
    }
    staging = {
      domain = "cdn-staging.plop.cool"
      bucket = "plop-staging-public"
    }
  }
  # CloudFront alias 레코드의 고정 호스티드존 ID(전역 상수).
  cloudfront_hosted_zone_id = "Z2FDTNDATAQYW2"
}

# ── 공개 버킷 (BPA 4종 ON — 공개 아님. CloudFront 만 OAC 로 읽음) ──────────
resource "aws_s3_bucket" "public" {
  for_each = local.cdn
  bucket   = each.value.bucket
  tags     = { Name = each.value.bucket }
}

resource "aws_s3_bucket_public_access_block" "public" {
  for_each                = local.cdn
  bucket                  = aws_s3_bucket.public[each.key].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ── CloudFront OAC (origin 접근 제어 — 버킷을 공개로 안 풀고 CloudFront 만 SigV4 로 읽게) ──
resource "aws_cloudfront_origin_access_control" "public" {
  for_each                          = local.cdn
  name                              = "${each.value.bucket}-oac"
  description                       = "OAC for ${each.value.domain}"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# ── ACM 인증서 (CloudFront 는 us-east-1 필수) ───────────────────────────
resource "aws_acm_certificate" "cdn" {
  for_each          = local.cdn
  provider          = aws.us_east_1
  domain_name       = each.value.domain
  validation_method = "DNS"
  lifecycle {
    create_before_destroy = true
  }
  tags = { Name = each.value.domain }
}

# ── Route53 존 (Squarespace 에서 위임받음 — persistent) ──────────────────
resource "aws_route53_zone" "cdn" {
  for_each = local.cdn
  name     = each.value.domain
  comment  = "plop CDN (${each.key}) — Squarespace 에서 위임. ALIAS → CloudFront."
}

# ACM DNS 검증 레코드 (단일 도메인 → 검증옵션 1개).
resource "aws_route53_record" "cdn_cert_validation" {
  for_each = {
    for k, cert in aws_acm_certificate.cdn : k => {
      name   = tolist(cert.domain_validation_options)[0].resource_record_name
      type   = tolist(cert.domain_validation_options)[0].resource_record_type
      record = tolist(cert.domain_validation_options)[0].resource_record_value
    }
  }
  zone_id         = aws_route53_zone.cdn[each.key].zone_id
  name            = each.value.name
  type            = each.value.type
  ttl             = 300
  records         = [each.value.record]
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "cdn" {
  for_each                = local.cdn
  provider                = aws.us_east_1
  certificate_arn         = aws_acm_certificate.cdn[each.key].arn
  validation_record_fqdns = [aws_route53_record.cdn_cert_validation[each.key].fqdn]
}

# ── CloudFront 배포 ─────────────────────────────────────────────────────
resource "aws_cloudfront_distribution" "public" {
  for_each        = local.cdn
  enabled         = true
  is_ipv6_enabled = true
  comment         = "plop public images (${each.key})"
  aliases         = [each.value.domain]
  price_class     = "PriceClass_200" # 북미/유럽/아시아(한국 엣지 포함). All 보다 저렴.

  # 원본 origin — S3(OAC). default behavior 가 여기로 (원본/웹/SSG/og:image).
  origin {
    domain_name              = aws_s3_bucket.public[each.key].bucket_regional_domain_name
    origin_id                = "s3-${each.value.bucket}"
    origin_access_control_id = aws_cloudfront_origin_access_control.public[each.key].id
  }

  # 변환 origin — 리전 Lambda(Function URL). /r/* behavior 만 여기로 (온디맨드 리사이즈/포맷).
  # 인증=시크릿 헤더(B): x-origin-secret 을 origin 에 주입, Lambda 가 검증(cdn-transform.tf 참고).
  # 변환이 깨져도 원본(위 S3 origin)은 영향 없음(fail-safe). 장기적으로 Lambda@Edge(C)로 대체 예정.
  origin {
    domain_name = trimsuffix(trimprefix(aws_lambda_function_url.image[each.key].function_url, "https://"), "/")
    origin_id   = "lambda-${each.value.bucket}"
    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
    custom_header {
      name  = "x-origin-secret"
      value = random_password.origin_secret[each.key].result
    }
  }

  default_cache_behavior {
    target_origin_id       = "s3-${each.value.bucket}"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    # Managed-CachingOptimized (AWS 관리형 캐시 정책 — 전역 고정 ID).
    cache_policy_id = "658327ea-f89d-4fab-a63d-7e88639e58f6"
  }

  # 리사이즈/포맷 변환 — /r/{key}?w=&h=&fm=&q=&fit= → 변환 Lambda. 쿼리를 캐시키에 포함.
  ordered_cache_behavior {
    path_pattern           = "/r/*"
    target_origin_id       = "lambda-${each.value.bucket}"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    # 캐시키에 포함된 쿼리(w/h/fm/q/fit)는 origin(Lambda)으로 자동 포워딩. Host 는 포워딩 안 함(OAC SigV4).
    cache_policy_id = aws_cloudfront_cache_policy.image_transform.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.cdn[each.key].certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  tags = { Name = "${each.value.bucket}-cdn" }
}

# 버킷 정책 — 이 배포(CloudFront 서비스 프린시펄 + SourceArn 조건)만 GetObject. (공개 정책 아님 → BPA 와 양립.)
resource "aws_s3_bucket_policy" "public" {
  for_each = local.cdn
  bucket   = aws_s3_bucket.public[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AllowCloudFrontOACReadOnly"
      Effect    = "Allow"
      Principal = { Service = "cloudfront.amazonaws.com" }
      Action    = "s3:GetObject"
      Resource  = "${aws_s3_bucket.public[each.key].arn}/*"
      Condition = {
        StringEquals = {
          "AWS:SourceArn" = aws_cloudfront_distribution.public[each.key].arn
        }
      }
    }]
  })
}

# ── cdn 도메인 → CloudFront (ALIAS, IPv4 + IPv6) ────────────────────────
resource "aws_route53_record" "cdn_alias_a" {
  for_each = local.cdn
  zone_id  = aws_route53_zone.cdn[each.key].zone_id
  name     = each.value.domain
  type     = "A"
  alias {
    name                   = aws_cloudfront_distribution.public[each.key].domain_name
    zone_id                = local.cloudfront_hosted_zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "cdn_alias_aaaa" {
  for_each = local.cdn
  zone_id  = aws_route53_zone.cdn[each.key].zone_id
  name     = each.value.domain
  type     = "AAAA"
  alias {
    name                   = aws_cloudfront_distribution.public[each.key].domain_name
    zone_id                = local.cloudfront_hosted_zone_id
    evaluate_target_health = false
  }
}
