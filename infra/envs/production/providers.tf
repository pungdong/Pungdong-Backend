terraform {
  required_version = ">= 1.6"

  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }

  # state 백엔드 — staging 과 같은 bootstrap 버킷/lock, key 만 production.
  backend "s3" {
    bucket         = "plop-tfstate-111328750981"
    key            = "production/terraform.tfstate"
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
      Env     = "production"
      Managed = "terraform"
    }
  }
}
