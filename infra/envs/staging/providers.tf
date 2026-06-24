terraform {
  required_version = ">= 1.6"

  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }

  # state 백엔드 — bootstrap 이 만든 S3 버킷 + DynamoDB lock.
  # 최초 1회: `terraform init` (bootstrap apply 후 bucket 이름을 아래에 맞춤).
  backend "s3" {
    bucket         = "pungdong-tfstate" # bootstrap 출력값으로 교체
    key            = "staging/terraform.tfstate"
    region         = "ap-northeast-2"
    dynamodb_table = "pungdong-tflock"
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
