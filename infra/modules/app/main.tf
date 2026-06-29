# 앱 계층 — ECR 이미지로 도는 ECS Fargate 서비스 + ALB + IAM + 로그 + 업로드 S3.
# 배포는 rolling(무중단 근접): 새 태스크 헬스 통과 후 옛 태스크 드레인.

data "aws_caller_identity" "current" {}

locals {
  uploads_bucket = coalesce(var.uploads_bucket_name, "${var.name_prefix}-uploads")
}

# --- 이미지 업로드 S3 (STORAGE_S3_ENABLED=true 에서 사용) ---
resource "aws_s3_bucket" "uploads" {
  bucket = local.uploads_bucket
  tags   = merge(var.tags, { Name = local.uploads_bucket })
}

resource "aws_s3_bucket_public_access_block" "uploads" {
  bucket                  = aws_s3_bucket.uploads.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# --- CloudWatch 로그 ---
resource "aws_cloudwatch_log_group" "this" {
  name              = "/ecs/${var.name_prefix}"
  retention_in_days = 14
  tags              = var.tags
}

# --- ECS 클러스터 ---
resource "aws_ecs_cluster" "this" {
  name = "${var.name_prefix}-cluster"
  tags = var.tags
}

# --- IAM: 실행 역할(ECR pull·로그·SSM 시크릿 복호화) ---
resource "aws_iam_role" "execution" {
  name = "${var.name_prefix}-ecs-exec"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# SSM SecureString 읽기 + KMS 복호화 (secrets 가 있을 때만).
resource "aws_iam_role_policy" "execution_secrets" {
  count = length(var.secrets) > 0 ? 1 : 0
  name  = "${var.name_prefix}-exec-secrets"
  role  = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ssm:GetParameters", "ssm:GetParameter"]
        Resource = values(var.secrets)
      },
      {
        Effect   = "Allow"
        Action   = "kms:Decrypt"
        Resource = "*" # SSM 기본 관리형 키. 전용 CMK 쓰면 그 ARN 으로 좁힐 것.
      }
    ]
  })
}

# --- IAM: 태스크 역할(앱 런타임 권한 — 업로드 버킷) ---
resource "aws_iam_role" "task" {
  name = "${var.name_prefix}-ecs-task"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
  tags = var.tags
}

resource "aws_iam_role_policy" "task_s3" {
  name = "${var.name_prefix}-task-s3"
  role = aws_iam_role.task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      [{
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject", "s3:ListBucket"]
        Resource = [aws_s3_bucket.uploads.arn, "${aws_s3_bucket.uploads.arn}/*"]
      }],
      # 공개 이미지 버킷(persistent dns 레이어 소유, cdn.tf)에 업로드 권한. ARN 문자열 참조라
      # 이 state 에 버킷 리소스가 없어도 됨(다른 state 가 소유). 읽기는 CloudFront 가 함.
      var.public_bucket_name == "" ? [] : [{
        Effect   = "Allow"
        Action   = ["s3:PutObject"]
        Resource = ["arn:aws:s3:::${var.public_bucket_name}/*"]
      }]
    )
  })
}

# --- 태스크 정의 (Fargate) ---
resource "aws_ecs_task_definition" "this" {
  family                   = var.name_prefix
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  # ARM64(Graviton) Fargate — x86 대비 ~20% 저렴 + 개발자 Mac(arm64)에서 네이티브 빌드(에뮬레이션 X).
  # 순수 Java 앱이라 아키텍처 무관. 이미지를 반드시 같은 아키텍처로 빌드해야 함(docker build --platform).
  runtime_platform {
    cpu_architecture        = var.cpu_architecture
    operating_system_family = "LINUX"
  }

  container_definitions = jsonencode([{
    name      = "app"
    image     = var.container_image
    essential = true
    portMappings = [{
      containerPort = var.container_port
      protocol      = "tcp"
    }]
    environment = [for k, v in var.environment : { name = k, value = v }]
    secrets     = [for k, v in var.secrets : { name = k, valueFrom = v }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.this.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "app"
      }
    }
  }])

  tags = var.tags
}

# --- ALB + 타겟그룹 + 리스너 ---
resource "aws_lb" "this" {
  name               = "${var.name_prefix}-alb"
  load_balancer_type = "application"
  subnets            = var.public_subnet_ids
  security_groups    = [var.alb_sg_id]
  tags               = var.tags
}

resource "aws_lb_target_group" "this" {
  name        = "${var.name_prefix}-tg"
  port        = var.container_port
  protocol    = "HTTP"
  target_type = "ip" # Fargate awsvpc = IP 타겟
  vpc_id      = data.aws_subnet.first.vpc_id

  health_check {
    path                = var.health_check_path
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  deregistration_delay = 30 # 드레인(in-flight 요청 처리)
  tags                 = var.tags
}

# target group 의 vpc_id 는 subnet 으로부터 역참조.
data "aws_subnet" "first" {
  id = var.public_subnet_ids[0]
}

# 인증서 없으면 HTTP(80) 포워드. 있으면 HTTP→HTTPS 리다이렉트 + HTTPS 포워드.
resource "aws_lb_listener" "http_forward" {
  count             = var.certificate_arn == null ? 1 : 0
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this.arn
  }
}

resource "aws_lb_listener" "http_redirect" {
  count             = var.certificate_arn == null ? 0 : 1
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  count             = var.certificate_arn == null ? 0 : 1
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this.arn
  }
}

# --- ECS 서비스 (rolling) ---
resource "aws_ecs_service" "this" {
  name            = "${var.name_prefix}-svc"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  # rolling: 새 태스크 띄우고(최대 200%) 헬스 통과 후 옛 태스크 드레인(최소 100%).
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  health_check_grace_period_seconds  = 120 # Spring 부팅 동안 ALB 헬스체크 유예

  network_configuration {
    subnets          = var.public_subnet_ids
    security_groups  = [var.app_sg_id]
    assign_public_ip = true # NAT 없이 public subnet 에서 ECR pull·아웃바운드
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.this.arn
    container_name   = "app"
    container_port   = var.container_port
  }

  depends_on = [
    aws_lb_listener.http_forward,
    aws_lb_listener.http_redirect,
    aws_lb_listener.https,
  ]

  # CI 가 새 이미지로 task definition 을 갱신·배포하므로 그 변화는 무시(드리프트 방지).
  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }

  tags = var.tags
}
