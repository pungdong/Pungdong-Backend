# Squarespace 위임에 쓸 nameserver. 각 서브도메인을 NS 레코드로 이 4개에 위임.
output "api_zone_nameservers" {
  description = "Squarespace: api 를 NS 로 이 값들에 위임"
  value       = aws_route53_zone.api.name_servers
}

output "api_staging_zone_nameservers" {
  description = "Squarespace: api-staging 를 NS 로 이 값들에 위임"
  value       = aws_route53_zone.api_staging.name_servers
}

# 공개 이미지 CDN — cdn / cdn-staging 도 NS 로 위임(api 와 동일 일회성). cdn.tf 참고.
output "cdn_zone_nameservers" {
  description = "Squarespace: cdn(.plop.cool) 를 NS 로 이 값들에 위임"
  value       = aws_route53_zone.cdn["prod"].name_servers
}

output "cdn_staging_zone_nameservers" {
  description = "Squarespace: cdn-staging(.plop.cool) 를 NS 로 이 값들에 위임"
  value       = aws_route53_zone.cdn["staging"].name_servers
}

output "cdn_domains" {
  description = "공개 이미지 CDN 도메인 (앱 STORAGE_PUBLIC_BASE_URL 에 https:// 붙여 사용)"
  value       = { for k, c in local.cdn : k => c.domain }
}
