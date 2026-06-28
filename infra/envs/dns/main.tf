# plop.cool 의 API 서브도메인 DNS 를 Route53 으로 관리.
#
# 왜: 도메인 DNS 는 Squarespace(옛 Google Domains)에 있는데 API/terraform provider 가 없어
# 자동화 불가. staging ALB 는 띄울 때마다 DNS 가 바뀌어(churn) 매번 수동 편집이 필요했음.
# → api / api-staging 서브도메인만 Route53 으로 위임받아 ALIAS(→ALB)로 관리하면 churn 자동 추적.
# 웹(@/www/staging = Vercel)·메일(MX = Google Workspace)은 Squarespace 그대로 둠(안 건드림).
#
# 위임(일회성, 사람 손): Squarespace DNS 에서 기존 api / api-staging CNAME + 각 ACM 검증 CNAME 을
# 지우고, api / api-staging 를 각각 NS 레코드로 아래 output 의 nameserver 4개에 위임.
#
# 이 레이어는 persistent — 존은 staging up/down 과 무관하게 살아있어야 위임이 안 깨짐.
# staging 의 ALIAS *레코드*는 ephemeral 이라 staging env(envs/staging/dns.tf)가 소유.

# ── prod: api.plop.cool → 상시 prod ALB ──────────────────────────────
resource "aws_route53_zone" "api" {
  name    = "api.plop.cool"
  comment = "plop API (prod) — Squarespace 에서 위임. ALIAS → prod ALB."
}

# 상시 떠 있는 prod ALB 를 이름으로 조회 (prod env state 와 분리).
data "aws_lb" "prod" {
  name = "plop-prod-alb"
}

resource "aws_route53_record" "api_alias" {
  zone_id = aws_route53_zone.api.zone_id
  name    = "api.plop.cool"
  type    = "A"
  alias {
    name                   = data.aws_lb.prod.dns_name
    zone_id                = data.aws_lb.prod.zone_id
    evaluate_target_health = true
  }
}

# ACM(api.plop.cool) DNS 검증 — 서브도메인이 Route53 으로 위임되므로 자동 renewal 위해 여기 있어야 함.
# (값은 현행 발급된 인증서의 검증 레코드. 인증서 재발급 시 갱신 필요.)
resource "aws_route53_record" "api_cert_validation" {
  zone_id = aws_route53_zone.api.zone_id
  name    = "_92579a5a5144985b1655a188b789b2ac.api.plop.cool"
  type    = "CNAME"
  ttl     = 300
  records = ["_32182853c030b7a3a0416b5f256f3491.jkddzztszm.acm-validations.aws."]
}

# ── staging: api-staging.plop.cool 존(persistent) ────────────────────
# ALIAS 레코드는 여기 없음 — staging ALB 가 ephemeral 이라 envs/staging/dns.tf 가 up/down 에 맞춰 관리.
resource "aws_route53_zone" "api_staging" {
  name    = "api-staging.plop.cool"
  comment = "plop API (staging) — Squarespace 에서 위임. ALIAS 는 staging env 가 up 때 생성."
}

resource "aws_route53_record" "api_staging_cert_validation" {
  zone_id = aws_route53_zone.api_staging.zone_id
  name    = "_dd49b8f6ffa986bd474cc4ec0539b0f4.api-staging.plop.cool"
  type    = "CNAME"
  ttl     = 300
  records = ["_71646856cbb89871e6cb0511856c96fc.jkddzztszm.acm-validations.aws."]
}
