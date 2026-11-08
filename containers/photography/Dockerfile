FROM eclipse-temurin:25.0.4_7-jre-noble@sha256:b573af9e331196fbc42e246da4df24df9b6c556c73e7efddfde0511f1c9508c5

COPY --chown=1000:1000 app/lib/ /app/lib/
RUN mkdir -p /data /thumbnails /photos && chown 1000:1000 /data /thumbnails

WORKDIR /app
ENV PHOTOGRAPHY_HOST=0.0.0.0
USER 1000:1000
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx2g", "-Djava.awt.headless=true", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-cp", "/app/lib/*", "photography.app.runtime.Main"]
