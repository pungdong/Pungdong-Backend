output "alb_dns_name" {
  description = "ALB DNS 이름 (DNS CNAME / Route53 alias 대상)"
  value       = aws_lb.this.dns_name
}

output "alb_zone_id" {
  description = "ALB 호스티드존 ID (Route53 alias 용)"
  value       = aws_lb.this.zone_id
}

output "ecs_cluster_name" {
  description = "ECS 클러스터 이름 (CI 배포 대상)"
  value       = aws_ecs_cluster.this.name
}

output "ecs_service_name" {
  description = "ECS 서비스 이름 (CI 배포 대상)"
  value       = aws_ecs_service.this.name
}

output "uploads_bucket" {
  description = "이미지 업로드 S3 버킷 이름 (STORAGE_S3_BUCKET 에 주입)"
  value       = aws_s3_bucket.uploads.bucket
}

output "log_group_name" {
  description = "CloudWatch 로그 그룹"
  value       = aws_cloudwatch_log_group.this.name
}

output "task_execution_role_arn" {
  description = "ECS 실행 역할 ARN"
  value       = aws_iam_role.execution.arn
}
