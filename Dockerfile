# Domain MCP Server
# Downloads the JAR from the latest GitHub release

FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

ARG VERSION=1.4
RUN curl -fsSL -o app.jar \
    "https://github.com/waabox/domain-mcp-server/releases/download/v${VERSION}/domain-mcp-server-${VERSION}.jar"

ENV JAVA_OPTS="-Xmx512m"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS --enable-preview -jar /app/app.jar"]
