# 온디맨드 이미지 변환 — CloudFront /r/* behavior 의 origin(리전 Lambda + sharp).
#
# 원본은 S3 origin(cdn.tf) 그대로, 변환만 이 Lambda. 변환이 깨져도 원본/웹은 영향 없음(fail-safe).
# 결정·근거: backend docs/features/image-storage-and-serving.md §4. Lambda 소스: ../../image-lambda/.
#
# 인증 (B 시크릿 헤더, interim): Function URL=NONE + CloudFront 가 origin 에 x-origin-secret 주입 +
# Lambda 가 검증. 근본원인은 InvokeFunction 권한 누락(InvokeFunctionUrl+InvokeFunction 둘 다 필요) — 장기엔 OAC 복귀가 정석.
# 변환은 공개 이미지 전용 + IAM 롤은 공개 버킷 GetObject 만이라 잔여 리스크 낮음(§4).
#
# ⚠️ 배포 전 `cd ../../image-lambda && ./build.sh` 로 package/ 를 빌드해야 함(sharp 네이티브, arm64).

data "archive_file" "image_lambda" {
  type        = "zip"
  source_dir  = "${path.module}/../../image-lambda/package"
  output_path = "${path.module}/../../image-lambda/dist.zip"
}

# CloudFront 만 호출하도록 게이트하는 공유 시크릿(env=Lambda, origin custom header=CloudFront). 클라 노출 안 됨.
resource "random_password" "origin_secret" {
  for_each = local.cdn
  length   = 40
  special  = false
}

# Lambda 실행 역할 — 공개 버킷 GetObject + 로그.
resource "aws_iam_role" "image_lambda" {
  for_each = local.cdn
  name     = "${each.value.bucket}-img-lambda"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "image_lambda_logs" {
  for_each   = local.cdn
  role       = aws_iam_role.image_lambda[each.key].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy" "image_lambda_s3" {
  for_each = local.cdn
  name     = "${each.value.bucket}-img-lambda-s3"
  role     = aws_iam_role.image_lambda[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:GetObject"]
      Resource = "${aws_s3_bucket.public[each.key].arn}/*"
    }]
  })
}

resource "aws_lambda_function" "image" {
  for_each         = local.cdn
  function_name    = "${each.value.bucket}-image-transform"
  role             = aws_iam_role.image_lambda[each.key].arn
  filename         = data.archive_file.image_lambda.output_path
  source_code_hash = data.archive_file.image_lambda.output_base64sha256
  handler          = "index.handler"
  runtime          = "nodejs20.x"
  architectures    = ["arm64"]
  memory_size      = 1536 # 메모리↑ = CPU↑ = 변환 빠름. sharp 여유.
  timeout          = 30
  # reserved_concurrent_executions: 계정 unreserved 최소(10) 제약으로 미설정. 남용은 시크릿 게이트로
  # 차단(시크릿 없으면 즉시 403=저비용). 트래픽 늘면 계정 한도 상향 후 예약 캡 재검토.
  environment {
    variables = {
      PUBLIC_BUCKET = each.value.bucket
      ORIGIN_SECRET = random_password.origin_secret[each.key].result # CloudFront 만 이 값을 헤더로 보냄
    }
  }
  tags = { Name = "${each.value.bucket}-image-transform" }
}

# Function URL — NONE(공개 도달 가능하나 Lambda 가 x-origin-secret 으로 게이트, 위 주석 참고). 장기엔 OAC 복귀.
resource "aws_lambda_function_url" "image" {
  for_each           = local.cdn
  function_name      = aws_lambda_function.image[each.key].function_name
  authorization_type = "NONE"
}

# NONE Function URL 은 익명 주체(*)에 InvokeFunctionUrl(URL 호출) + InvokeFunction(함수 실행) 권한이
# 둘 다 있어야 호출 허용된다(콘솔은 자동 생성, Terraform/CLI 는 명시 필요 — 콘솔 배너가 명시).
# FunctionUrlAuthType=NONE 조건으로 "함수 URL 경유 호출"로 한정. 실제 접근은 Lambda 의 x-origin-secret 으로 게이트.
resource "aws_lambda_permission" "public_invoke_url" {
  for_each               = local.cdn
  statement_id           = "AllowPublicInvokeFunctionUrl"
  action                 = "lambda:InvokeFunctionUrl"
  function_name          = aws_lambda_function.image[each.key].function_name
  principal              = "*"
  function_url_auth_type = "NONE"
}

# InvokeFunction 은 FunctionUrlAuthType 조건을 못 받음(API 제약) → 조건 없이 *. NONE Function URL 호출이
# 실제 함수 실행까지 가려면 필요(콘솔 배너 지시). 실접근은 Lambda 의 x-origin-secret 으로 게이트되므로
# (Invoke API 직접 호출도 headers 없어 403) 잔여 리스크 낮음.
resource "aws_lambda_permission" "public_invoke_function" {
  for_each      = local.cdn
  statement_id  = "AllowPublicInvokeFunction"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.image[each.key].function_name
  principal     = "*"
}

# /r/* 캐시 정책 — 변환 쿼리(w/h/fm/q/fit)를 캐시키에 포함(+origin 으로 자동 포워딩). 나머지는 무시.
resource "aws_cloudfront_cache_policy" "image_transform" {
  name        = "plop-image-transform"
  comment     = "이미지 변환 — w/h/fm/q/fit 쿼리를 캐시키에 포함"
  default_ttl = 86400
  max_ttl     = 31536000
  min_ttl     = 0
  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_brotli = true
    enable_accept_encoding_gzip   = true
    cookies_config {
      cookie_behavior = "none"
    }
    headers_config {
      header_behavior = "none"
    }
    query_strings_config {
      query_string_behavior = "whitelist"
      query_strings {
        items = ["w", "h", "fm", "q", "fit"]
      }
    }
  }
}
