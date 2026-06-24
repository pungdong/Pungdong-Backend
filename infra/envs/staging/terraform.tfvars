# staging 값. 시크릿은 여기 두지 않는다(SSM). DB 비번은 Terraform 이 생성.
# container_image 는 CI 가 갱신 — 최초엔 placeholder(이미지 푸시 전이면 apply 후 CI 가 덮어씀).

container_image      = "PLACEHOLDER_ECR_IMAGE" # 예: <acct>.dkr.ecr.ap-northeast-2.amazonaws.com/pungdong:latest
cors_allowed_origins = "https://staging.plop.cool"

# HTTPS 인증서는 ⑥(ACM/DNS)에서. 그 전까진 HTTP 로 ALB 직접 접속해 검증.
# certificate_arn = "arn:aws:acm:ap-northeast-2:...:certificate/..."
