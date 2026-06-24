output "db_endpoint" {
  description = "RDS 호스트(주소). 앱의 spring.datasource.url 에 주입"
  value       = aws_db_instance.this.address
}

output "db_port" {
  description = "RDS 포트"
  value       = aws_db_instance.this.port
}

output "db_name" {
  description = "초기 데이터베이스 이름"
  value       = aws_db_instance.this.db_name
}

output "redis_endpoint" {
  description = "ElastiCache Redis 호스트(주소). 앱의 spring.redis.host 에 주입"
  value       = aws_elasticache_cluster.this.cache_nodes[0].address
}

output "redis_port" {
  description = "Redis 포트"
  value       = aws_elasticache_cluster.this.port
}
