FROM public.ecr.aws/docker/library/amazoncorretto:25

WORKDIR /app
COPY build/libs/llama-bench-db-0.1.0.jar ./app.jar

# PostgreSQL by default; override DB_URL/DB_USER/DB_PASSWORD at run time.
ENV SPRING_PROFILES_ACTIVE=postgres

EXPOSE 8080

USER 1000

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
