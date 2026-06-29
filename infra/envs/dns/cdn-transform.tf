# 온디맨드 이미지 변환 — CloudFront /r/* behavior 의 origin(리전 Lambda + sharp).
#
# 원본은 S3 origin(cdn.tf) 그대로, 변환만 이 Lambda. 변환이 깨져도 원본/웹은 영향 없음(fail-safe).
# 결정·근거: backend docs/features/image-storage-and-serving.md §4. Lambda 소스: ../../image-lambda/.
#
# ⚠️ 배포 전 `cd ../../image-lambda && ./build.sh` 로 package/ 를 빌드해야 함(sharp 네이티브, arm64).

data "archive_file" "image_lambda" {
  type        = "zip"
  source_dir  = "${path.module}/../../image-lambda/package"
  output_path = "${path.module}/../../image-lambda/dist.zip"
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
  environment {
    variables = { PUBLIC_BUCKET = each.value.bucket }
  }
  tags = { Name = "${each.value.bucket}-image-transform" }
}

# Function URL — AWS_IAM(CloudFront OAC SigV4 로만 호출, 공개 아님).
resource "aws_lambda_function_url" "image" {
  for_each           = local.cdn
  function_name      = aws_lambda_function.image[each.key].function_name
  authorization_type = "AWS_IAM"
}

# CloudFront → Lambda Function URL 용 OAC.
resource "aws_cloudfront_origin_access_control" "image_lambda" {
  for_each                          = local.cdn
  name                              = "${each.value.bucket}-lambda-oac"
  description                       = "OAC for ${each.value.domain} image transform lambda"
  origin_access_control_origin_type = "lambda"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# 이 배포만 Function URL 호출 허용 (SourceArn = 배포).
resource "aws_lambda_permission" "cloudfront_invoke_url" {
  for_each               = local.cdn
  statement_id           = "AllowCloudFrontInvokeFunctionUrl"
  action                 = "lambda:InvokeFunctionUrl"
  function_name          = aws_lambda_function.image[each.key].function_name
  principal              = "cloudfront.amazonaws.com"
  source_arn             = aws_cloudfront_distribution.public[each.key].arn
  function_url_auth_type = "AWS_IAM"
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
