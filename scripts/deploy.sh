#!/bin/bash
set -e
echo "=== SentinelRate Deploy to ECS ==="
[ -z "$ECR_URI" ] && echo "ERROR: ECR_URI not set" && exit 1
[ -z "$AWS_REGION" ] && echo "ERROR: AWS_REGION not set" && exit 1
mvn clean package -DskipTests
docker build -t sentinelrate .
aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $ECR_URI
docker tag sentinelrate:latest $ECR_URI/sentinelrate:latest
docker push $ECR_URI/sentinelrate:latest
aws ecs update-service --cluster sentinelrate-cluster --service sentinelrate-svc --force-new-deployment --region $AWS_REGION
echo "=== Deploy triggered. Monitor: AWS ECS console ==="
