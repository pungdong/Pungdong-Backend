output "alb_dns_name" {
  description = "ALB 주소. DNS(api-staging.plop.cool)를 여기로 CNAME, 또는 임시로 직접 접속"
  value       = module.app.alb_dns_name
}

output "alb_zone_id" {
  value = module.app.alb_zone_id
}

output "ecs_cluster_name" {
  description = "CI(⑤) 배포 대상 클러스터"
  value       = module.app.ecs_cluster_name
}

output "ecs_service_name" {
  description = "CI(⑤) 배포 대상 서비스"
  value       = module.app.ecs_service_name
}

output "uploads_bucket" {
  value = module.app.uploads_bucket
}

output "db_endpoint" {
  value = module.data.db_endpoint
}
