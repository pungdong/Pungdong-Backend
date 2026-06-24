# staging 값. 시크릿은 여기 두지 않는다(SSM). DB 비번은 Terraform 이 생성.

# 이미지 태그 — 빌드한 이미지를 ECR(plop)에 이 태그로 push 후 apply. CI(⑤)가 자동 갱신할 자리.
image_tag            = "staging-latest"
cors_allowed_origins = "https://staging.plop.cool"

# HTTPS 인증서는 ⑥(ACM/DNS)에서. 그 전까진 HTTP 로 ALB 직접 접속해 검증.
# certificate_arn = "arn:aws:acm:ap-northeast-2:...:certificate/..."
