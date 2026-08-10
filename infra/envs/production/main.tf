# production 환경 — 모듈(network/data/app) 조합 + 운영값. 상시 가동.
# staging 과 같은 모듈·같은 ECR 이미지(build-once → promote), 값만 운영용.

data "aws_caller_identity" "current" {}

locals {
  name_prefix    = "plop-prod"
  ssm_prefix     = "/plop/production"
  account_id     = data.aws_caller_identity.current.account_id
  uploads_bucket = "${local.name_prefix}-uploads"
  # 공개 이미지 버킷 + CDN — persistent dns 레이어(cdn.tf)가 소유. 여기선 이름/도메인만 참조.
  public_bucket = "${local.name_prefix}-public"
  cdn_base_url  = "https://cdn.plop.cool"

  container_image = "${local.account_id}.dkr.ecr.${var.aws_region}.amazonaws.com/${var.ecr_repo_name}:${var.image_tag}"

  # connectionTimeZone=UTC (+forceConnectionTimeZoneToSession): instant(OffsetDateTime) 을 UTC 로 저장/조회.
  # app application.yml 의 hibernate.jdbc.time_zone=UTC 와 한 쌍 (글로벌화 UTC 통일, docs/architecture/time-handling.md).
  db_url = "jdbc:mysql://${module.data.db_endpoint}:${module.data.db_port}/${module.data.db_name}?characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true"

  # 운영 시크릿은 /plop/production/<NAME> (staging 과 분리). 사용자가 SSM 에 미리 생성.
  # TOSS_*: 토스페이먼츠 결제위젯 키(테스트). 토스는 휴면(PAYMENT_MODE=inicis) 이지만 과거 주문 환불 라우팅용으로 유지.
  # INICIS_*: 이니시스 서명 키(운영 MID plopol1192 값 — 2026-08-10 _LIVE 로 스왑). client-key 는 공개값이지만 SSM 에 같이 넣어 일괄 참조.
  # SANITY_TOKEN: legal 프록시(GET /legal/{slug})가 Sanity legalDocument 를 읽는 Viewer read 토큰
  #   (legalDocument 가 익명 거부라 토큰 필요 — legal/CLAUDE.md). ⚠️ SSM 에 미리 넣어야 task 기동.
  user_secret_names = ["JWT_SECRET", "ADMIN_MAIL_ID", "ADMIN_MAIL_PASSWORD", "JUSO_SEARCH_KEY", "JUSO_COORD_KEY", "TOSS_SECRET_KEY", "TOSS_CLIENT_KEY", "INICIS_HASH_KEY", "INICIS_API_KEY", "SANITY_TOKEN"]
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
    # 공개 이미지(코스/프로필/리뷰) — 공개 버킷에 올리고 CDN URL 로 서빙. (자격증=비공개 uploads 버킷.)
    CLOUD_AWS_S3_PUBLIC_BUCKET = local.public_bucket
    STORAGE_PUBLIC_BASE_URL    = local.cdn_base_url
    IDENTITY_VERIFICATION_MODE = "stub" # 실 본인확인기관 연동 전까지(심사용). 정식 출시 전 disabled/real 검토.
    ADDRESS_GEOCODE_MODE       = "juso"
    JUSO_REFERER               = "https://plop.cool" # 운영 juso 키 등록 referer 와 일치
    # 🧪 seeded 강의 가용시간 개방(신청 가능하게). (자동수락 DEMO_AUTO_ACCEPT 은 선결제 전환으로 제거됨 —
    # 결제가 강사 수락 앞으로 와서 자동수락이 원천 불필요.)
    DEMO_SEED_AVAILABILITY = "true"
    # 결제: 토스 → 이니시스 스왑. 토스 테스트 키의 결제위젯이 prod 에서 variantKey 오류로 아예 안 떠
    # (심사자가 결제창을 못 봄), 스테이징에서 실 왕복 검증된(#192) 이니시스로 맞춘다.
    # ⚠️ MID 와 키는 **짝**이다 — 섞으면 결제창이 인증 전에 거절(콜백 P_OID=null P_STATUS=01).
    # ✅ 2026-08-10 운영 MID(plopol1192)로 flip — 사이트 심사 통과로 결제창이 열리고 카드 인증(콜백
    #    P_STATUS=00)까지 성공 확인. 최종 서버승인만 서브몰 카드사 심사 미완료로 00HH("서브가맹점 정보
    #    미확인", 8-10 요청·7~10영업일) — 심사 완료 후 제3자 카드로 실결제 왕복 재검증 예정.
    #    운영 키는 SSM /plop/production/INICIS_{HASH,API}_KEY 에 이미 반영(_LIVE 값으로 스왑) → MID 와 짝.
    #    _LIVE 파라미터는 백업으로 유지. 테스트 MID 로 원복 시 키 소스 = /plop/staging/INICIS_*(동일 테스트키).
    # ⚠️ 이 flip 은 이니시스 어댑터(PaymentProvider.INICIS)가 든 이미지와 **같은 task def revision** 으로만 나가야 한다
    #    (옛 이미지 + inicis mode = valueOf 실패로 앱 전체 부팅 실패). 순서는 deployment.md "PG 스왑" 런북.
    PAYMENT_MODE              = "inicis"
    INICIS_MID                = "plopol1192" # 운영 MID (2026-08-10 flip, SSM 운영키 _LIVE 와 짝)
    INICIS_RET_URL            = "https://api.plop.cool/payments/inicis/return"
    INICIS_RETURN_WEB_SUCCESS = "https://plop.cool/payment/success"
    INICIS_RETURN_WEB_FAIL    = "https://plop.cool/payment/fail"
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

  # 운영: 삭제보호 + destroy 시 최종 스냅샷 보존. 백업 보관일은 무료플랜 제한으로 1일
  # (유료 전환 후 7일로 상향 — FreeTierRestrictionError 회피).
  deletion_protection       = true
  backup_retention_period   = 1
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
  public_bucket_name  = local.public_bucket
  environment         = local.environment
  secrets             = local.secrets
  certificate_arn     = var.certificate_arn
  desired_count       = 1
}
