# staging 값. 시크릿은 여기 두지 않는다(SSM). DB 비번은 Terraform 이 생성.

# 이미지 태그 — 빌드한 이미지를 ECR(plop)에 이 태그로 push 후 apply. CI(⑤)가 자동 갱신할 자리.
image_tag            = "staging-latest"
cors_allowed_origins = "https://staging.plop.cool"

# HTTPS — ACM 인증서(api-staging.plop.cool, DNS 검증 완료 2026-06-24).
# 이게 있어야 ALB 443 리스너 + 80→443 리다이렉트가 생김(없으면 HTTP only 로 회귀).
certificate_arn = "arn:aws:acm:ap-northeast-2:111328750981:certificate/75f29a3c-7e32-495e-a67f-8d2b9d6a2795"
