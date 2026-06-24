# staging 환경 — 모듈(network/data/app) 조합 + staging 값.
# 안 쓸 땐 `terraform destroy`($0, 최종 스냅샷만), 쓸 땐 `apply`.

data "aws_caller_identity" "current" {}

locals {
  name_prefix    = "plop-staging"
  ssm_prefix     = "/plop/staging"
  account_id     = data.aws_caller_identity.current.account_id
  uploads_bucket = "${local.name_prefix}-uploads"

  # 공유 ECR(bootstrap) 의 이미지 URI 조립.
  container_image = "${local.account_id}.dkr.ecr.${var.aws_region}.amazonaws.com/${var.ecr_repo_name}:${var.image_tag}"

  # 앱이 RDS/Redis 에 붙는 URL — 모듈 output 에서 조립.
  # useSSL=false: 트래픽이 VPC 내부 + data SG(app 에서만)라 RDS 인증서 검증 마찰 회피. (prod 는 SSL+RDS CA 검토)
  db_url = "jdbc:mysql://${module.data.db_endpoint}:${module.data.db_port}/${module.data.db_name}?characterEncoding=UTF-8&serverTimezone=Asia/Seoul&useSSL=false&allowPublicKeyRetrieval=true"

  # 사용자가 SSM 콘솔에 미리 만들 SecureString. (container env var 이름 = SSM 파라미터 이름)
  # 경로: /plop/staging/<NAME>
  user_secret_names = ["JWT_SECRET", "ADMIN_MAIL_ID", "ADMIN_MAIL_PASSWORD"]
  user_secrets = {
    for n in local.user_secret_names :
    n => "arn:aws:ssm:${var.aws_region}:${local.account_id}:parameter${local.ssm_prefix}/${n}"
  }
  secrets = merge(local.user_secrets, {
    SPRING_DATASOURCE_PASSWORD = aws_ssm_parameter.db_password.arn
  })

  # 앱 일반 환경변수(비밀 아님). database/redis/aws.yml 을 classpath 에서 빼고
  # (SPRING_CONFIG_LOCATION=classpath:application.yml) 표준 env 키로 주입(Spring relaxed binding,
  # env > application.yml 우선). AWS 자격증명은 안 줌 → ECS task role 자동 사용.
  #
  # ④ application.yml ${ENV} 전수 조사 반영:
  # - cloud.aws.region.static / stack.auto 는 application.yml 에 이미 박혀 있어 생략.
  # - S3 버킷: S3Uploader 가 ${cloud.aws.s3.bucket}(application.yml 하드코딩 "pungdong")를 읽으므로
  #   CLOUD_AWS_S3_BUCKET 로 덮어써야 우리 버킷을 가리킴 (STORAGE_S3_BUCKET 은 앱이 안 읽음).
  # - JWT_SECRET/ADMIN_MAIL_* 만 필수 secret. SANITY_*(public 기본값)·VENUE_RECONCILE_*·webhook 은 기본값 사용.
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
    IDENTITY_VERIFICATION_MODE = "stub"
    ADDRESS_GEOCODE_MODE       = "stub"
  }
}

# DB 마스터 비밀번호: 자동 생성 → SSM SecureString. 사람이 안 만짐.
resource "random_password" "db" {
  length  = 24
  special = false # RDS 비번 특수문자 제약 회피
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

  # staging 온디맨드: destroy 시 스냅샷 보존 / 복원
  skip_final_snapshot         = false
  final_snapshot_identifier   = var.final_snapshot_identifier
  restore_snapshot_identifier = var.restore_snapshot_identifier
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
