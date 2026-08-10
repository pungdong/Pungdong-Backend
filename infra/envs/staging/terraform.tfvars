# staging 값. 시크릿은 여기 두지 않는다(SSM). DB 비번은 Terraform 이 생성.

# 이미지 태그는 여기서 정하지 않는다 — main.tf 에 master-latest 로 <b>고정</b>(변수 자체를 없앴다).
# 왜: sha 로 핀되면 force-new-deployment 가 조용히 무력화된다(재시작해도 같은 이미지, 배포는 "성공"). 상세는 main.tf.
cors_allowed_origins = "https://staging.plop.cool,https://admin-staging.plop.cool"

# HTTPS — ACM 인증서(api-staging.plop.cool, DNS 검증 완료 2026-06-24).
# 이게 있어야 ALB 443 리스너 + 80→443 리다이렉트가 생김(없으면 HTTP only 로 회귀).
certificate_arn = "arn:aws:acm:ap-northeast-2:111328750981:certificate/75f29a3c-7e32-495e-a67f-8d2b9d6a2795"
