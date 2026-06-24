output "vpc_id" {
  description = "VPC ID"
  value       = aws_vpc.this.id
}

output "public_subnet_ids" {
  description = "public subnet ID 목록 (ALB·ECS task·DB subnet group 에서 사용)"
  value       = aws_subnet.public[*].id
}

output "alb_sg_id" {
  description = "ALB 보안그룹 ID"
  value       = aws_security_group.alb.id
}

output "app_sg_id" {
  description = "ECS task 보안그룹 ID"
  value       = aws_security_group.app.id
}

output "data_sg_id" {
  description = "RDS/Redis 보안그룹 ID"
  value       = aws_security_group.data.id
}
