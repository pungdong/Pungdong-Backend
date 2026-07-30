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
  identifier = "${var.name_prefix}-mysql"
  engine     = "mysql"

  # 메이저만 핀 → 마이너(8.4.x)는 AWS 최신 선택 + auto minor upgrade. 마이너 드리프트로 plan 이 흔들리지 않음.
  # 8.0 → 8.4 (2026-07-30): 8.0 은 2026-07-31 RDS 표준지원 종료.
  #   방치하면 8/1부터 Extended Support 과금 = 서울 $0.12/vCPU-h × 2 vCPU × 2대 ≈ $350/월 (인스턴스 원가의 ~10배, 크레딧 9일치).
  #   ⚠️ Extended Support 등록은 ModifyDBInstance 에 필드가 없어 기존 인스턴스에서 해제 불가 → 메이저 업그레이드가 유일한 회피책이었음.
  #   ⚠️ 그러니 engine_lifecycle_support 를 여기 추가하지 말 것 — 생성/복원 시에만 지정 가능한 인수라 인스턴스 교체(=prod DB 파괴)를 유발한다.
  engine_version = "8.4"
  # 메이저 업그레이드는 이 플래그가 같은 apply 안에 있어야 modify 가 통과한다. (자동 실행 트리거가 아니라 "허용"일 뿐 —
  # engine_version 리터럴을 바꾸지 않으면 TF 가 버전을 건드리지 않음.)
  allow_major_version_upgrade = true
  instance_class              = var.db_instance_class

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
