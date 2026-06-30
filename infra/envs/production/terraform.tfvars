# 운영 값. 시크릿은 SSM(/plop/production/*). DB 비번은 Terraform 생성.

# prod 는 검증된 이미지를 <b>핀(pin)</b> 한다(floating master-latest 아님 — 미검증 master 가 prod 로 새는 것 방지).
# staging 검증 후 이 sha 를 bump. (옛 핀 master-517ad3f 는 ECR 에서 만료/제거돼 직접 apply 시 CannotPull 이었음.)
# 2026-06-30: 이미지 파이프라인(자격증 presigned·공개 CDN·보험·V6) 검증 완료 → master-0745573 로 bump.
# ⚠️ 직접 terraform apply 금지에 가깝게 — prod 실배포는 production-deploy 워크플로(명시 image_tag)로.
image_tag            = "master-0745573"
cors_allowed_origins = "https://plop.cool,https://www.plop.cool,https://admin.plop.cool"

# HTTPS 인증서 (api.plop.cool, DNS 검증 완료).
certificate_arn = "arn:aws:acm:ap-northeast-2:111328750981:certificate/b3cf9b22-36b9-4c78-8bcc-e12dff660604"
