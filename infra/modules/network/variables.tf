variable "name_prefix" {
  description = "리소스 이름 접두어 (예: pungdong-staging)"
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR 블록"
  type        = string
  default     = "10.0.0.0/16"
}

variable "az_count" {
  description = "사용할 가용영역 수. RDS subnet group·ALB 가 최소 2개 AZ 를 요구 → 기본 2"
  type        = number
  default     = 2
}

variable "app_port" {
  description = "컨테이너(Spring Boot) 포트 — ALB 가 이 포트로 헬스체크·포워딩"
  type        = number
  default     = 8080
}

variable "tags" {
  description = "공통 태그"
  type        = map(string)
  default     = {}
}
