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

output "state_bucket" {
  description = "envs/*/providers.tf 의 backend.bucket 에 넣을 값"
  value       = aws_s3_bucket.tfstate.bucket
}

output "lock_table" {
  value = aws_dynamodb_table.tflock.name
}
