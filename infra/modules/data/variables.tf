variable "name_prefix" {
  description = "리소스 이름 접두어 (예: plop-staging)"
  type        = string
}

variable "subnet_ids" {
  description = "DB/Redis subnet group 에 쓸 subnet ID 목록 (최소 2개 AZ)"
  type        = list(string)
}

variable "data_sg_id" {
  description = "RDS/Redis 보안그룹 ID (app 에서만 인바운드 허용)"
  type        = string
}

# --- RDS MySQL ---

variable "db_name" {
  description = "초기 데이터베이스 이름"
  type        = string
  default     = "pungdong"
}

variable "db_username" {
  description = "마스터 사용자명"
  type        = string
  default     = "pungdong"
}

variable "db_password" {
  description = "마스터 비밀번호 (SSM SecureString 에서 주입 — tfvars 에 평문 금지)"
  type        = string
  sensitive   = true
}

variable "db_instance_class" {
  description = "RDS 인스턴스 클래스 (프리티어 = db.t4g.micro)"
  type        = string
  default     = "db.t4g.micro"
}

variable "db_allocated_storage" {
  description = "RDS 스토리지 GB (프리티어 20GB)"
  type        = number
  default     = 20
}

variable "db_multi_az" {
  description = "Multi-AZ 여부 (출시 lean = false)"
  type        = bool
  default     = false
}

variable "deletion_protection" {
  description = "RDS 삭제 보호 (prod=true 권장)"
  type        = bool
  default     = false
}

variable "backup_retention_period" {
  description = "자동 백업 보관일 (staging=1, prod=7 권장)"
  type        = number
  default     = 1
}

# --- staging 온디맨드: destroy 시 최종 스냅샷 보존 / 재생성 시 복원 ---

variable "skip_final_snapshot" {
  description = "destroy 시 최종 스냅샷 생략 여부. staging/prod 모두 false(보존) 권장. throwaway 면 true"
  type        = bool
  default     = false
}

variable "final_snapshot_identifier" {
  description = "destroy 시 만들 최종 스냅샷 이름. skip_final_snapshot=false 면 필수. 재destroy 충돌 피하려 사이클마다 유니크하게(예: plop-staging-final-20260624)"
  type        = string
  default     = null
}

variable "restore_snapshot_identifier" {
  description = "이 스냅샷에서 RDS 를 복원 생성(이전 staging 데이터 살리기). 비우면 빈 DB 신규 생성"
  type        = string
  default     = null
}

# --- ElastiCache Redis ---

variable "redis_node_type" {
  description = "ElastiCache 노드 타입 (프리티어 = cache.t3.micro)"
  type        = string
  default     = "cache.t3.micro"
}

variable "tags" {
  description = "공통 태그"
  type        = map(string)
  default     = {}
}
