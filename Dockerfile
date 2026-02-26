# ===== Build stage =====
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

# ===== Run stage (léger) =====
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar /app/app.jar
ARG SEREVER_PORT=8090
ENV SEREVER_PORT=${SEREVER_PORT}
EXPOSE ${SEREVER_PORT}
ENTRYPOINT ["java","-jar","/app/app.jar"]
