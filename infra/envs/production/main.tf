# production 환경 — 모듈(network/data/app) 조합 + 운영값. 상시 가동.
# staging 과 같은 모듈·같은 ECR 이미지(build-once → promote), 값만 운영용.

data "aws_caller_identity" "current" {}

locals {
  name_prefix    = "plop-prod"
  ssm_prefix     = "/plop/production"
  account_id     = data.aws_caller_identity.current.account_id
  uploads_bucket = "${local.name_prefix}-uploads"

  container_image = "${local.account_id}.dkr.ecr.${var.aws_region}.amazonaws.com/${var.ecr_repo_name}:${var.image_tag}"

  db_url = "jdbc:mysql://${module.data.db_endpoint}:${module.data.db_port}/${module.data.db_name}?characterEncoding=UTF-8&serverTimezone=Asia/Seoul&useSSL=false&allowPublicKeyRetrieval=true"

  # 운영 시크릿은 /plop/production/<NAME> (staging 과 분리). 사용자가 SSM 에 미리 생성.
  # juso 는 운영 키 등록 후 추가(현재 ADDRESS_GEOCODE_MODE=stub).
  user_secret_names = ["JWT_SECRET", "ADMIN_MAIL_ID", "ADMIN_MAIL_PASSWORD"]
  user_secrets = {
    for n in local.user_secret_names :
    n => "arn:aws:ssm:${var.aws_region}:${local.account_id}:parameter${local.ssm_prefix}/${n}"
  }
  secrets = merge(local.user_secrets, {
    SPRING_DATASOURCE_PASSWORD = aws_ssm_parameter.db_password.arn
  })

  environment = {
    SPRING_CONFIG_LOCATION     = "classpath:application.yml"
    SPRING_DATASOURCE_URL      = local.db_url
    SPRING_DATASOURCE_USERNAME = "pungdong"
    SPRING_REDIS_HOST          = module.data.redis_endpoint
    SPRING_REDIS_PORT          = tostring(module.data.redis_port)
    CORS_ALLOWED_ORIGINS       = var.cors_allowed_origins
    ADMIN_EMAILS               = var.admin_emails
    FIREBASE_ENABLED           = "false"
    STORAGE_S3_ENABLED         = "true"
    CLOUD_AWS_S3_BUCKET        = local.uploads_bucket
    IDENTITY_VERIFICATION_MODE = "stub" # 실 본인확인기관 연동 전까지(심사용). 정식 출시 전 disabled/real 검토.
    ADDRESS_GEOCODE_MODE       = "stub" # 운영 juso 키 등록 후 juso 로 전환.
  }
}

# DB 마스터 비밀번호: 자동 생성 → SSM SecureString.
resource "random_password" "db" {
  length  = 24
  special = false
}

resource "aws_ssm_parameter" "db_password" {
  name  = "${local.ssm_prefix}/SPRING_DATASOURCE_PASSWORD"
  type  = "SecureString"
  value = random_password.db.result
  tags  = { Name = "${local.name_prefix}-db-password" }
}

module "network" {
  source      = "../../modules/network"
  name_prefix = local.name_prefix
}

module "data" {
  source      = "../../modules/data"
  name_prefix = local.name_prefix
  subnet_ids  = module.network.public_subnet_ids
  data_sg_id  = module.network.data_sg_id
  db_password = random_password.db.result

  # 운영: 삭제보호 + 백업 7일. destroy 시 최종 스냅샷 보존(throwaway 아님).
  deletion_protection       = true
  backup_retention_period   = 7
  skip_final_snapshot       = false
  final_snapshot_identifier = var.final_snapshot_identifier
}

module "app" {
  source              = "../../modules/app"
  name_prefix         = local.name_prefix
  aws_region          = var.aws_region
  public_subnet_ids   = module.network.public_subnet_ids
  alb_sg_id           = module.network.alb_sg_id
  app_sg_id           = module.network.app_sg_id
  container_image     = local.container_image
  uploads_bucket_name = local.uploads_bucket
  environment         = local.environment
  secrets             = local.secrets
  certificate_arn     = var.certificate_arn
  desired_count       = 1
}
