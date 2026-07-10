# staging 환경 — 모듈(network/data/app) 조합 + staging 값.
# 안 쓸 땐 `terraform destroy`($0, 최종 스냅샷만), 쓸 땐 `apply`.

data "aws_caller_identity" "current" {}

locals {
  name_prefix    = "plop-staging"
  ssm_prefix     = "/plop/staging"
  account_id     = data.aws_caller_identity.current.account_id
  uploads_bucket = "${local.name_prefix}-uploads"
  # 공개 이미지 버킷 + CDN — persistent dns 레이어(cdn.tf)가 소유. 여기선 이름/도메인만 참조.
  public_bucket = "${local.name_prefix}-public"
  cdn_base_url  = "https://cdn-staging.plop.cool"

  # 공유 ECR(bootstrap) 의 이미지 URI 조립.
  container_image = "${local.account_id}.dkr.ecr.${var.aws_region}.amazonaws.com/${var.ecr_repo_name}:${var.image_tag}"

  # 앱이 RDS/Redis 에 붙는 URL — 모듈 output 에서 조립.
  # useSSL=false: 트래픽이 VPC 내부 + data SG(app 에서만)라 RDS 인증서 검증 마찰 회피. (prod 는 SSL+RDS CA 검토)
  # connectionTimeZone=UTC (+forceConnectionTimeZoneToSession): instant(OffsetDateTime) 을 UTC 로 저장/조회.
  # app application.yml 의 hibernate.jdbc.time_zone=UTC 와 한 쌍 (글로벌화 UTC 통일, docs/architecture/time-handling.md).
  db_url = "jdbc:mysql://${module.data.db_endpoint}:${module.data.db_port}/${module.data.db_name}?characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true"

  # 사용자가 SSM 콘솔에 미리 만들 SecureString. (container env var 이름 = SSM 파라미터 이름)
  # 경로: /plop/staging/<NAME>
  # TOSS_*: 토스 결제위젯 키(테스트). SANITY_TOKEN: legal 프록시용 Viewer read 토큰(legal/CLAUDE.md).
  # ⚠️ staging-up 전에 /plop/staging/{TOSS_SECRET_KEY,TOSS_CLIENT_KEY,SANITY_TOKEN} 를 SSM 에 미리
  # 넣어야 task 가 기동된다(secret 미존재면 ECS 가 task 시작 실패). SANITY_TOKEN 은 이미 존재.
  user_secret_names = ["JWT_SECRET", "ADMIN_MAIL_ID", "ADMIN_MAIL_PASSWORD", "JUSO_SEARCH_KEY", "JUSO_COORD_KEY", "TOSS_SECRET_KEY", "TOSS_CLIENT_KEY", "SANITY_TOKEN"]
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
    # FCM: WIF 키리스(JSON 키 없음). task role(plop-staging-ecs-task) → GCP SA 가장.
    # GCP 신뢰 설정(pool/provider/binding)은 gcloud 로 1회 구성됨 — docs/features/push.md.
    FIREBASE_ENABLED                   = "true"
    FIREBASE_WIF_AUDIENCE              = "//iam.googleapis.com/projects/956872568873/locations/global/workloadIdentityPools/aws-pool/providers/aws-provider"
    FIREBASE_WIF_SERVICE_ACCOUNT_EMAIL = "firebase-adminsdk-fbsvc@plop-5997b.iam.gserviceaccount.com"
    # WIF(external_account) 자격엔 project id 가 없어 Admin SDK 가 FCM 엔드포인트
    # (/v1/projects/<id>/messages:send)를 못 만든다 → project id 를 표준 env 로 명시.
    GOOGLE_CLOUD_PROJECT = "plop-5997b"
    AWS_REGION           = var.aws_region
    STORAGE_S3_ENABLED   = "true"
    CLOUD_AWS_S3_BUCKET  = local.uploads_bucket
    # 공개 이미지(코스/프로필/리뷰) — 공개 버킷에 올리고 CDN URL 로 서빙. (자격증=비공개 uploads 버킷.)
    CLOUD_AWS_S3_PUBLIC_BUCKET = local.public_bucket
    STORAGE_PUBLIC_BASE_URL    = local.cdn_base_url
    IDENTITY_VERIFICATION_MODE = "stub"
    ADDRESS_GEOCODE_MODE       = "juso"
    JUSO_REFERER               = "https://staging.plop.cool"
    # 결제: 토스 실연동(PG 심사용). 키는 위 secrets(SSM) 주입. 현재 테스트 키(실결제 X).
    PAYMENT_MODE = "toss"
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
  public_bucket_name  = local.public_bucket
  environment         = local.environment
  secrets             = local.secrets
  certificate_arn     = var.certificate_arn
  desired_count       = 1
}
