# 네트워크 토대 — VPC + public subnet + IGW + 보안그룹.
# lean 구성: NAT gateway 없음(태스크를 public subnet 에 두고 public IP 부여로 아웃바운드 확보).
# 보안은 SG 로 좁힌다: 외부 → ALB 만, ALB → app 만, app → data 만.

data "aws_availability_zones" "available" {
  state = "available"
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = merge(var.tags, { Name = "${var.name_prefix}-vpc" })
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = "${var.name_prefix}-igw" })
}

# public subnet × az_count. /20 씩 잘라 AZ 분산 (ALB·RDS subnet group 이 2개 AZ 요구).
resource "aws_subnet" "public" {
  count                   = var.az_count
  vpc_id                  = aws_vpc.this.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, count.index)
  availability_zone       = data.aws_availability_zones.available.names[count.index]
  map_public_ip_on_launch = true
  tags                    = merge(var.tags, { Name = "${var.name_prefix}-public-${count.index}" })
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }
  tags = merge(var.tags, { Name = "${var.name_prefix}-public-rt" })
}

resource "aws_route_table_association" "public" {
  count          = var.az_count
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# --- 보안그룹: 외부 → ALB → app → data 단방향 ---

# ALB: 인터넷에서 80/443.
resource "aws_security_group" "alb" {
  name_prefix = "${var.name_prefix}-alb-"
  description = "ALB ingress (80/443 from internet)"
  vpc_id      = aws_vpc.this.id

  ingress {
    description = "HTTP"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = merge(var.tags, { Name = "${var.name_prefix}-alb-sg" })
  lifecycle { create_before_destroy = true }
}

# app(ECS task): ALB 에서만 app_port 인바운드.
resource "aws_security_group" "app" {
  name_prefix = "${var.name_prefix}-app-"
  description = "ECS task ingress (app_port from ALB only)"
  vpc_id      = aws_vpc.this.id

  ingress {
    description     = "from ALB"
    from_port       = var.app_port
    to_port         = var.app_port
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = merge(var.tags, { Name = "${var.name_prefix}-app-sg" })
  lifecycle { create_before_destroy = true }
}

# data(RDS·Redis): app 에서만 3306/6379 인바운드. 외부 노출 없음.
resource "aws_security_group" "data" {
  name_prefix = "${var.name_prefix}-data-"
  description = "RDS/Redis ingress (3306/6379 from app only)"
  vpc_id      = aws_vpc.this.id

  ingress {
    description     = "MySQL from app"
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }
  ingress {
    description     = "Redis from app"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = merge(var.tags, { Name = "${var.name_prefix}-data-sg" })
  lifecycle { create_before_destroy = true }
}
