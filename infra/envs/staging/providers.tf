terraform {
  required_version = ">= 1.6"

  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }

  # state 백엔드 — bootstrap 이 만든 S3 버킷 + DynamoDB lock (2026-06-24 생성됨).
  backend "s3" {
    bucket         = "plop-tfstate-111328750981" # bootstrap state_bucket 출력값
    key            = "staging/terraform.tfstate"
    region         = "ap-northeast-2"
    dynamodb_table = "plop-tflock"
    encrypt        = true
  }
}

provider "aws" {
  region = var.aws_region
  default_tags {
    tags = {
      Project = "pungdong"
      Env     = "staging"
      Managed = "terraform"
    }
  }
}
