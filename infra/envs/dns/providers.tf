terraform {
  required_version = ">= 1.6"

  required_providers {
    aws     = { source = "hashicorp/aws", version = "~> 5.0" }
    archive = { source = "hashicorp/archive", version = "~> 2.4" }
    random  = { source = "hashicorp/random", version = "~> 3.6" } # origin_secret destroy 후 미사용이나 유지(state 정리용)
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

# CloudFront 인증서(ACM)는 us-east-1 에서만 인식된다 — cdn.tf 의 aws_acm_certificate 전용.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"
  default_tags {
    tags = {
      Project = "pungdong"
      Managed = "terraform-dns"
    }
  }
}
