#!/bin/bash
# Build, push to ECR, and deploy to ECS Fargate.
# Required env vars: ECR_REGISTRY, AWS_REGION, ECS_CLUSTER, ECS_SERVICE
# Optional env vars: APP_URL (enables post-deploy smoke test)
set -euo pipefail

ECR_REPOSITORY="fluxguard"

echo "=== FluxGuard — Deploy to ECS ==="

# ── Validate required env vars ──────────────────────────────────────────────
: "${ECR_REGISTRY:?ECR_REGISTRY not set}"
: "${AWS_REGION:?AWS_REGION not set}"
: "${ECS_CLUSTER:?ECS_CLUSTER not set}"
: "${ECS_SERVICE:?ECS_SERVICE not set}"

IMAGE_TAG=$(git rev-parse --short HEAD)
FULL_IMAGE="${ECR_REGISTRY}/${ECR_REPOSITORY}"

echo "[1/5] Building JAR (tests skipped)..."
mvn clean package -DskipTests -q

echo "[2/5] Building Docker image..."
docker build -t "${FULL_IMAGE}:${IMAGE_TAG}" -t "${FULL_IMAGE}:latest" .

echo "[3/5] Pushing to ECR..."
aws ecr get-login-password --region "${AWS_REGION}" \
  | docker login --username AWS --password-stdin "${ECR_REGISTRY}"
docker push "${FULL_IMAGE}:${IMAGE_TAG}"
docker push "${FULL_IMAGE}:latest"

echo "[4/5] Triggering ECS deployment (cluster=${ECS_CLUSTER} service=${ECS_SERVICE})..."
aws ecs update-service \
  --cluster "${ECS_CLUSTER}" \
  --service "${ECS_SERVICE}" \
  --force-new-deployment \
  --region "${AWS_REGION}" \
  --output text --query 'service.serviceName' > /dev/null

echo "      Waiting for service to stabilise (timeout: 10 min)..."
aws ecs wait services-stable \
  --cluster "${ECS_CLUSTER}" \
  --services "${ECS_SERVICE}" \
  --region "${AWS_REGION}"

echo "[5/5] Post-deploy smoke test..."
if [ -n "${APP_URL:-}" ]; then
  STATUS=$(curl -sf -o /dev/null -w "%{http_code}" "${APP_URL}/actuator/health" || echo "000")
  if [ "${STATUS}" = "200" ]; then
    echo "      Health check passed (HTTP 200)."
  else
    echo "ERROR: Health check returned HTTP ${STATUS} — deployment may be unhealthy." >&2
    exit 1
  fi
else
  echo "      APP_URL not set — skipping health check."
fi

echo "=== Deploy complete. Image: ${FULL_IMAGE}:${IMAGE_TAG} ==="
