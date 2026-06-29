variable "name_prefix" {
  description = "리소스 이름 접두어 (예: plop-staging)"
  type        = string
}

variable "aws_region" {
  description = "리전 (CloudWatch 로그 설정용)"
  type        = string
  default     = "ap-northeast-2"
}

variable "public_subnet_ids" {
  description = "ALB·ECS task 가 위치할 public subnet"
  type        = list(string)
}

variable "alb_sg_id" {
  description = "ALB 보안그룹"
  type        = string
}

variable "app_sg_id" {
  description = "ECS task 보안그룹"
  type        = string
}

variable "container_image" {
  description = "ECR 이미지 URI:tag (CI 가 푸시한 것). 예: <acct>.dkr.ecr.ap-northeast-2.amazonaws.com/pungdong:<sha>"
  type        = string
}

variable "container_port" {
  description = "컨테이너 포트"
  type        = number
  default     = 8080
}

variable "cpu" {
  description = "Fargate task CPU (256=0.25 / 512=0.5 vCPU)"
  type        = number
  default     = 512
}

variable "memory" {
  description = "Fargate task 메모리 MB (512/1024/2048...)"
  type        = number
  default     = 1024
}

variable "cpu_architecture" {
  description = "Fargate CPU 아키텍처. ARM64(Graviton)=저렴+Mac 네이티브 빌드 / X86_64"
  type        = string
  default     = "ARM64"
}

variable "desired_count" {
  description = "원하는 태스크 수. prod=1. staging 도 1(안 쓸 땐 env 통째로 destroy)"
  type        = number
  default     = 1
}

variable "environment" {
  description = "컨테이너 일반 환경변수 (비밀 아님). 예: SPRING_CONFIG_LOCATION, SPRING_DATASOURCE_URL, CORS_ALLOWED_ORIGINS"
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "컨테이너 비밀 환경변수: 이름 → SSM Parameter ARN (SecureString). 실행 역할이 런타임에 복호화 주입"
  type        = map(string)
  default     = {}
}

variable "health_check_path" {
  description = "ALB 헬스체크 경로 (actuator)"
  type        = string
  default     = "/actuator/health"
}

variable "certificate_arn" {
  description = "ACM 인증서 ARN. 주면 HTTPS(443) 리스너 + 80→443 리다이렉트. null 이면 HTTP(80) 만 (초기·심사 전)"
  type        = string
  default     = null
}

variable "uploads_bucket_name" {
  description = "이미지 업로드 S3 버킷 이름 (전역 유니크). 비우면 <name_prefix>-uploads 로 생성"
  type        = string
  default     = null
}

variable "public_bucket_name" {
  description = "공개 이미지 버킷 이름 (persistent dns 레이어가 생성, cdn.tf). 주면 task role 에 PutObject 권한 추가. 비우면 생략."
  type        = string
  default     = ""
}

variable "tags" {
  description = "공통 태그"
  type        = map(string)
  default     = {}
}
