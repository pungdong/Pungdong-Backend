variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

variable "ecr_repo_name" {
  description = "공유 ECR 저장소 이름 (bootstrap 의 ecr_repo_name 과 동일)"
  type        = string
  default     = "plop"
}

variable "image_tag" {
  description = "배포할 이미지 태그 (git sha 또는 latest). CI(⑤)가 갱신"
  type        = string
  default     = "latest"
}

variable "cors_allowed_origins" {
  description = "CORS 허용 오리진 (staging 웹)"
  type        = string
  default     = "https://staging.plop.cool"
}

variable "admin_emails" {
  description = "부팅 시 ROLE_ADMIN 부여할 이메일(콤마구분). 어드민 심사 페이지 접근용"
  type        = string
  default     = "haneojin@plop.cool"
}

variable "certificate_arn" {
  description = "ACM 인증서 ARN (HTTPS). 초기 HTTP 검증 단계에선 null"
  type        = string
  default     = null
}

variable "final_snapshot_identifier" {
  description = "staging destroy 시 만들 최종 스냅샷 이름 (사이클마다 유니크). 예: plop-staging-final-20260624"
  type        = string
  default     = "plop-staging-final"
}

variable "restore_snapshot_identifier" {
  description = "이전 스냅샷에서 staging RDS 복원. 비우면 빈 DB 신규"
  type        = string
  default     = null

  # ⚠️ MySQL 8.0 스냅샷으로 복원하지 말 것 (2026-07-31 표준지원 종료 이후).
  #    복원된 인스턴스는 Extended Support 8.0 마이너로 자동 승격 + 그 순간부터 과금(서울 $0.12/vCPU-h ≈ $175/월/대)되고,
  #    등록 해제는 ModifyDBInstance 로 불가하다 = 다시 8.4 로 메이저 업그레이드해야 멈춘다.
  #    8.0 시절 스냅샷(예: 2026-06-25 생성 plop-staging-final)은 복원 대상에서 제외 — 빈 DB(null)로 올리거나 8.4 이후 final 스냅샷만 사용.
  #    스냅샷 이름만 봐선 구분이 안 되니 복원 전에 반드시 엔진 버전을 확인할 것:
  #      aws rds describe-db-snapshots --snapshot-type manual --query 'DBSnapshots[].[DBSnapshotIdentifier,EngineVersion]' --output table
}
