# api-staging.plop.cool → staging ALB (ALIAS).
#
# 존 자체는 persistent dns 레이어(envs/dns)가 소유 — 여기선 staging up/down 에 맞춰 ALIAS *레코드*만
# 생성/제거한다. staging 을 다시 띄우면 ALB DNS 가 바뀌어도(churn) terraform 이 새 ALB 로 자동 갱신
# → 더 이상 Squarespace 에서 수동 편집 불필요. (ALIAS 라 TTL ~60초로 전파도 빠름.)
data "aws_route53_zone" "api_staging" {
  name = "api-staging.plop.cool."
}

resource "aws_route53_record" "api_staging_alias" {
  zone_id = data.aws_route53_zone.api_staging.zone_id
  name    = "api-staging.plop.cool"
  type    = "A"
  alias {
    name                   = module.app.alb_dns_name
    zone_id                = module.app.alb_zone_id
    evaluate_target_health = true
  }
}
