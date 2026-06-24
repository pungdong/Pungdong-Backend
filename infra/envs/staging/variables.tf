variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

variable "container_image" {
  description = "배포할 ECR 이미지 URI:tag. CI 가 갱신하므로 초기엔 placeholder 가능"
  type        = string
}

variable "cors_allowed_origins" {
  description = "CORS 허용 오리진 (staging 웹)"
  type        = string
  default     = "https://staging.plop.cool"
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
