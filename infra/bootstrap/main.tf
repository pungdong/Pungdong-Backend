# Terraform state 백엔드 부트스트랩 — S3(상태 저장) + DynamoDB(동시 apply lock).
# 닭-달걀 회피: 이 디렉토리는 *로컬 state* 로 1회 apply (state 백엔드 자체를 만드는 곳이라).
# 이후 envs/* 가 이 S3 버킷을 backend 로 사용.
#
# 사용:
#   cd infra/bootstrap
#   terraform init && terraform apply
#   → 출력된 state_bucket 이름을 envs/*/providers.tf 의 backend.bucket 에 반영.

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
  # backend 없음 = 로컬 state (bootstrap 은 거의 안 바뀜).
}

provider "aws" {
  region = var.aws_region
  default_tags {
    tags = {
      Project = "pungdong"
      Managed = "terraform-bootstrap"
    }
  }
}

variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

variable "state_bucket_name" {
  description = "Terraform state S3 버킷 (전역 유니크). 기본값이 이미 쓰이면 접미사 추가"
  type        = string
  default     = "plop-tfstate-111328750981"
}

variable "lock_table_name" {
  type    = string
  default = "plop-tflock"
}

variable "ecr_repo_name" {
  description = "공유 ECR 저장소 이름. staging/prod 가 같은 repo 의 다른 태그를 가리킴"
  type        = string
  default     = "plop"
}

resource "aws_s3_bucket" "tfstate" {
  bucket = var.state_bucket_name
}

resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket                  = aws_s3_bucket.tfstate.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_dynamodb_table" "tflock" {
  name         = var.lock_table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"
  attribute {
    name = "LockID"
    type = "S"
  }
}

# --- 공유 ECR (이미지 저장소) ---
# bootstrap 에 두는 이유: staging 을 destroy 해도 이미지가 안 날아가야 하고,
# build-once → staging→prod 같은 이미지 promote 도 가능(둘이 같은 repo 참조).
resource "aws_ecr_repository" "app" {
  name                 = var.ecr_repo_name
  image_tag_mutability = "MUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
}

# 오래된 미태그/구버전 이미지 자동 정리(스토리지 비용 절감).
resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "최근 10개만 유지"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 10 }
      action       = { type = "expire" }
    }]
  })
}

output "state_bucket" {
  description = "envs/*/providers.tf 의 backend.bucket 에 넣을 값"
  value       = aws_s3_bucket.tfstate.bucket
}

output "lock_table" {
  value = aws_dynamodb_table.tflock.name
}

output "ecr_repository_url" {
  description = "이미지 push 대상 + staging/prod container_image 베이스. <acct>.dkr.ecr.<region>.amazonaws.com/<name>"
  value       = aws_ecr_repository.app.repository_url
}
