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
}
