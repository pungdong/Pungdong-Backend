# staging 값. 시크릿은 여기 두지 않는다(SSM). DB 비번은 Terraform 이 생성.

# 이미지 태그 — staging 은 최신 master 추종. build.yml 이 master 머지마다 master-latest 를 ECR 에 push.
# (옛 기본값 "staging-latest" 는 ECR 에 존재하지 않는 promote 라벨이라 직접 apply 시 CannotPull 함정이었음 — master-latest 로 교정.)
# 워크플로 staging-up 도 -var="image_tag=master-latest" 로 동일하게 띄움.
image_tag            = "master-latest"
cors_allowed_origins = "https://staging.plop.cool,https://admin-staging.plop.cool"

# HTTPS — ACM 인증서(api-staging.plop.cool, DNS 검증 완료 2026-06-24).
# 이게 있어야 ALB 443 리스너 + 80→443 리다이렉트가 생김(없으면 HTTP only 로 회귀).
certificate_arn = "arn:aws:acm:ap-northeast-2:111328750981:certificate/75f29a3c-7e32-495e-a67f-8d2b9d6a2795"
