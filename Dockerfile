FROM amazoncorretto:25-al2023-headless

WORKDIR /app

ARG XMX=1280m
ENV JAVA_OPTS="-Xms512m -Xmx${XMX} -XX:+UseContainerSupport"

COPY build/libs/*.jar app.jar

RUN mkdir -p /app/logs

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]