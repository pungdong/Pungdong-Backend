# Squarespace 위임에 쓸 nameserver. 각 서브도메인을 NS 레코드로 이 4개에 위임.
output "api_zone_nameservers" {
  description = "Squarespace: api 를 NS 로 이 값들에 위임"
  value       = aws_route53_zone.api.name_servers
}

output "api_staging_zone_nameservers" {
  description = "Squarespace: api-staging 를 NS 로 이 값들에 위임"
  value       = aws_route53_zone.api_staging.name_servers
}
