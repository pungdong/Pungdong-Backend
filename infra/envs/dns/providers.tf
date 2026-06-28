terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }

  # persistent DNS 레이어 — staging/prod 와 별도 state. (존은 staging churn 과 무관하게 살아있어야
  # Squarespace NS 위임이 안 깨짐.) bootstrap 이 만든 동일 버킷/lock 사용, key 만 분리.
  backend "s3" {
    bucket         = "plop-tfstate-111328750981"
    key            = "dns/terraform.tfstate"
    region         = "ap-northeast-2"
    dynamodb_table = "plop-tflock"
    encrypt        = true
  }
}

provider "aws" {
  region = "ap-northeast-2"
  default_tags {
    tags = {
      Project = "pungdong"
      Managed = "terraform-dns"
    }
  }
}
