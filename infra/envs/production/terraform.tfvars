# 운영 값. 시크릿은 SSM(/plop/production/*). DB 비번은 Terraform 생성.

# staging 에서 검증된 이미지를 prod 태그로 promote 후 그 태그 지정.
# 2026-06-26: 공모전/PG심사 데모 — master-517ad3f(#97 FcmGateway 부팅수정 포함) 로 promote.
image_tag            = "master-517ad3f"
cors_allowed_origins = "https://plop.cool,https://www.plop.cool,https://admin.plop.cool"

# HTTPS 인증서 (api.plop.cool, DNS 검증 완료).
certificate_arn = "arn:aws:acm:ap-northeast-2:111328750981:certificate/b3cf9b22-36b9-4c78-8bcc-e12dff660604"
