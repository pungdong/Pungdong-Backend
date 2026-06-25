# 데이터 계층 — RDS MySQL + ElastiCache Redis.
# 둘 다 public subnet 에 두되 publicly_accessible=false + data SG(app 에서만) 로 외부 비노출.
# (RDS 는 아웃바운드 인터넷 불필요 → NAT 없이도 OK.)

# --- RDS MySQL ---

resource "aws_db_subnet_group" "this" {
  name       = "${var.name_prefix}-db"
  subnet_ids = var.subnet_ids
  tags       = merge(var.tags, { Name = "${var.name_prefix}-db-subnet" })
}

resource "aws_db_instance" "this" {
  identifier     = "${var.name_prefix}-mysql"
  engine         = "mysql"
  engine_version = "8.0"
  instance_class = var.db_instance_class

  allocated_storage = var.db_allocated_storage
  storage_type      = "gp2" # 프리티어 = 20GB GP SSD
  multi_az          = var.db_multi_az

  db_name  = var.db_name
  username = var.db_username
  password = var.db_password

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [var.data_sg_id]
  publicly_accessible    = false

  # staging 온디맨드: destroy 시 최종 스냅샷 보존 → 다음 apply 때 restore_snapshot_identifier 로 복원.
  skip_final_snapshot       = var.skip_final_snapshot
  final_snapshot_identifier = var.skip_final_snapshot ? null : var.final_snapshot_identifier
  snapshot_identifier       = var.restore_snapshot_identifier

  # 백업 보관·삭제보호는 env 가 결정 (staging=1일·off / prod=7일·on).
  backup_retention_period = var.backup_retention_period
  deletion_protection     = var.deletion_protection
  apply_immediately       = true

  tags = merge(var.tags, { Name = "${var.name_prefix}-mysql" })
}

# --- ElastiCache Redis (단일 노드) ---

resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name_prefix}-redis"
  subnet_ids = var.subnet_ids
}

resource "aws_elasticache_cluster" "this" {
  cluster_id           = "${var.name_prefix}-redis"
  engine               = "redis"
  node_type            = var.redis_node_type
  num_cache_nodes      = 1
  parameter_group_name = "default.redis7"
  port                 = 6379

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [var.data_sg_id]

  tags = merge(var.tags, { Name = "${var.name_prefix}-redis" })
}
