---
name: deploy
description: Deploy pungdong BE to staging and/or production. Handles the staging up/down → staging-up vs force-new-deployment decision, prod production-deploy with a pinned verified sha, Flyway-migration & env preflight, and post-deploy verification (image digest, health, migration logs). Use when asked to deploy / ship / release the BE to staging or prod, or to catch an environment up to master.
---

# 배포 (pungdong BE → staging / production)

풍덩 BE는 **build-once / 12-factor**: master 머지마다 `build.yml`이 이미지를 ECR에 굽고(`master-latest` + `master-<sha>`), 배포는 그 이미지를 ECS 서비스에 롤링으로 태우는 것. **terraform ≠ 배포 파이프라인** — 앱 배포는 `ecs update-service`(롤링)이고, 서비스에 `lifecycle.ignore_changes=[task_definition]`가 걸려 있어 terraform과 CI가 안 싸운다.

전체 배경/인시던트: [docs/architecture/deployment.md](../../../docs/architecture/deployment.md). 이 스킬은 그 절차의 실행 가이드.

**고정 리소스 (ap-northeast-2)**
- ECR repo: `plop` (`111328750981.dkr.ecr.ap-northeast-2.amazonaws.com/plop`), 태그 mutable
- staging: cluster `plop-staging-cluster` / service `plop-staging-svc` / logs `/ecs/plop-staging`
- prod: cluster `plop-prod-cluster` / service `plop-prod-svc` / logs `/ecs/plop-prod`, 헬스 `https://api.plop.cool/actuator/health`
- 배포 워크플로: `gh workflow run deploy.yml -f action=<staging-up|staging-down|production-deploy> [-f image_tag=...]`

---

## 0. 항상 먼저 — 프리플라이트

1. **무엇을 배포하나 확정.** `git rev-parse --short=7 origin/master` = 대상 sha. 대상 이미지 = `plop:master-<sha>`.
2. **이미지가 ECR에 있나** (Build-and-Push 완료돼야 함):
   ```
   gh run list --workflow "Build and Push (master to ECR)" --limit 1 --json status,conclusion,headSha
   aws ecr describe-images --repository-name plop --image-ids imageTag=master-<sha> --region ap-northeast-2 --query 'imageDetails[0].imageDigest'
   ```
   진행 중이면 완료까지 대기(`gh run watch <id> --exit-status`). **이미지 없이 배포하면 CannotPull.**
3. **그 환경이 지금 뭐에 떠 있나 → 델타 파악** (배포는 대상 sha까지 *여러 PR*을 점프시킬 수 있음):
   ```
   aws ecs describe-task-definition --task-definition <plop-staging|plop-prod>:<현재> --region ap-northeast-2 --query 'taskDefinition.containerDefinitions[0].image'
   git log --oneline <현재sha>..<대상sha>            # 무엇이 올라가나
   git diff --name-status <현재sha> <대상sha> -- src/main/resources/db/migration/   # 스키마 델타
   ```
4. **롤백 참조**: 배포 전 현재 이미지 태그를 기록해둔다.
5. **prod면 사용자 확인** — 특히 (a) 여러 PR 점프 (b) 새 Flyway 마이그레이션 (c) 새 필수 env. 범위를 사용자에게 알리고 go 받기. **머지는 사용자 몫이듯, prod 배포도 명시 승인.**

---

## 1. 스테이징 배포 — ★ 상태 확인이 진짜 1스텝

```
aws ecs describe-services --cluster plop-staging-cluster --services plop-staging-svc --region ap-northeast-2 --query 'services[0].{status:status,running:runningCount}'
```

- **DOWN(없음/destroyed)** → `gh workflow run deploy.yml -f action=staging-up`
  - staging은 온디맨드라 보통 이 경로. **fresh create 순간 terraform이 task def를 `master-latest`로 세팅 → 이미지 올바로 물림, force 불필요** (`ignore_changes`는 서비스가 *이미 존재할 때만* 발동).
- **UP(이미 떠 있음)** → **force-new-deployment** (staging-up 아님!):
  ```
  aws ecs update-service --cluster plop-staging-cluster --service plop-staging-svc --force-new-deployment --region ap-northeast-2
  aws ecs wait services-stable --cluster plop-staging-cluster --services plop-staging-svc --region ap-northeast-2
  ```
  - **왜**: 이미 떠 있으면 `staging-up`(terraform apply)은 `ignore_changes=[task_definition]` 때문에 **이미지를 조용히 안 바꾼다**(에러도 없어 "배포됐겠지" 착각). task def가 mutable `master-latest` 참조라 force-new-deployment가 재-pull로 최신 반영. `staging-down`+`up`은 QA 중단이라 부적절.
- **검증**: 실행 태스크 digest == ECR `master-latest` digest, 헬스 UP.
  ```
  T=$(aws ecs list-tasks --cluster plop-staging-cluster --service-name plop-staging-svc --region ap-northeast-2 --query 'taskArns[0]' --output text)
  aws ecs describe-tasks --cluster plop-staging-cluster --tasks "$T" --region ap-northeast-2 --query 'tasks[0].containers[0].imageDigest'
  aws ecr describe-images --repository-name plop --image-ids imageTag=master-latest --region ap-northeast-2 --query 'imageDetails[0].imageDigest'   # 둘이 일치해야
  ```

**직접 apply 불가피할 때만**: `terraform apply -var="image_tag=master-latest"`. tfvars 기본값 함정 주의(옛 `staging-latest`는 ECR에 없음 → CannotPull). 그래도 서비스는 `ignore_changes`라 apply 뒤 force-new-deployment 필요.

---

## 2. 프로덕션 배포 — 핀된 sha로 워크플로

prod는 `master-latest`(floating) 아니라 **검증된 `master-<sha>`를 핀**한다(미검증 master가 prod로 새는 것 방지).

```
gh workflow run deploy.yml -f action=production-deploy -f image_tag=master-<sha>
gh run watch <run-id> --exit-status --interval 20
```

워크플로가 하는 일(= 떠 있는 서비스 무중단 롤링): 현재 task def 조회 → 새 이미지로 revision 렌더 → `amazon-ecs-deploy-task-definition` + `wait-for-service-stability` + 헬스확인. terraform 안 탐 → staging의 ignore_changes 함정 **면역**.

- **마이그레이션이 델타에 있으면** (프리플라이트 3): Flyway가 **앱 부팅 시** 자동 실행. 확인:
  - **멱등인가** — `CREATE TABLE IF NOT EXISTS` / `information_schema` 가드(MySQL은 `ADD/DROP COLUMN IF [NOT] EXISTS` 없음). ECS churn 동시실행 대비 필수([docs/architecture/deployment.md] 인시던트).
  - **fresh-DB로 이미 검증됐나** — staging에 먼저 배포돼 Flyway로 깔끔히 돌았으면(staging UP) 검증된 것. (H2 CI·`mysql <` 직접은 Flyway 실행을 검증 못 함.)
- **부팅 필수 env** — 새 코드가 요구하는 env가 prod task def에 있나(없고 default 없으면 fail-fast crash-loop). `${VAR:}`처럼 default 있으면 부팅은 됨(값 정합은 별개).
- **검증**:
  ```
  aws ecs describe-services --cluster plop-prod-cluster --services plop-prod-svc --region ap-northeast-2 --query 'services[0].deployments[0].rolloutState'   # COMPLETED
  # 실행 태스크 digest == ECR master-<sha> digest
  curl -s https://api.plop.cool/actuator/health    # {"status":"UP"}
  # 마이그레이션 있었으면 로그로 확인:
  aws logs filter-log-events --log-group-name /ecs/plop-prod --region ap-northeast-2 --start-time $(( ($(date +%s) - 1200) * 1000 )) --filter-pattern '?Migrating ?"Successfully applied"' --query 'events[].message' --output text
  ```
  참고: `hbm2ddl=validate`라 앱이 UP이면 스키마-엔티티 정합 = 마이그레이션 적용됐다는 강한 증거(누락 시 부팅 실패).

---

## 흔한 함정 (요약)
- **staging-up ≠ 이미지 새로고침** — 이미 떠 있으면 force-new-deployment. ([[admin_grant_and_staging_restart_ops]], [[feedback_staging_deploy_image_tag]])
- **prod는 sha 핀**, floating `master-latest` 금지. production-deploy 기본값 `master-latest`라 `-f image_tag=master-<sha>` 명시.
- **마이그레이션 멱등 + fresh-DB 검증** — 직접 `mysql <`나 H2 CI는 Flyway 실행을 검증 못 함. ([[feedback_migrations_idempotent]], [[prod_flyway_deploy_ops]])
- **직접 `update-service`는 원칙상 워크플로 경유** — 단 "떠 있는 staging 이미지 새로고침"은 force-new-deployment가 정식 메커니즘(GH Actions 무료초과 시 prod도 예외 허용).
- **"컨테이너/prod에서만 시간 어긋남" = TZ 누출** ([[feedback_container_tz_localdatetime]]).
