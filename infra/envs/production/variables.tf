variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

variable "ecr_repo_name" {
  description = "공유 ECR 저장소 이름 (bootstrap 과 동일)"
  type        = string
  default     = "plop"
}

variable "image_tag" {
  description = "배포할 이미지 태그. staging 에서 검증된 이미지를 prod 로 promote"
  type        = string
  default     = "prod-latest"
}

variable "cors_allowed_origins" {
  description = "CORS 허용 오리진 (운영 웹)"
  type        = string
  default     = "https://plop.cool,https://www.plop.cool"
}

variable "admin_emails" {
  description = "부팅 시 ROLE_ADMIN 부여할 이메일(콤마구분)"
  type        = string
  default     = "haneojin@plop.cool"
}

variable "certificate_arn" {
  description = "ACM 인증서 ARN (api.plop.cool HTTPS). 초기 HTTP 검증 단계엔 null"
  type        = string
  default     = null
}

variable "final_snapshot_identifier" {
  description = "destroy 시 최종 스냅샷 이름 (운영은 거의 destroy 안 함)"
  type        = string
  default     = "plop-prod-final"
}
