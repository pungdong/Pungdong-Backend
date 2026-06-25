output "alb_dns_name" {
  description = "ALB 주소. api.plop.cool 을 여기로 CNAME"
  value       = module.app.alb_dns_name
}

output "alb_zone_id" {
  value = module.app.alb_zone_id
}

output "ecs_cluster_name" {
  value = module.app.ecs_cluster_name
}

output "ecs_service_name" {
  value = module.app.ecs_service_name
}

output "uploads_bucket" {
  value = module.app.uploads_bucket
}

output "db_endpoint" {
  value = module.data.db_endpoint
}
