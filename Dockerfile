FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /workspace

COPY pom.xml ./
RUN mvn dependency:go-offline -q
COPY checkstyle ./checkstyle
COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine

RUN addgroup -S fluxguard && adduser -S fluxguard -G fluxguard

WORKDIR /app

COPY --from=builder /workspace/target/fluxguard-0.1.0-SNAPSHOT.jar /app/app.jar

USER fluxguard

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
