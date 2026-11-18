# mediagate SFTP 测试环境（隔离在容器里，不污染开发机）
FROM docker.m.daocloud.io/library/eclipse-temurin:21-jdk
WORKDIR /app
COPY jsch-2.28.7.jar ./
COPY *.java ./
RUN javac -cp jsch-2.28.7.jar *.java
ENTRYPOINT ["java", "-cp", "jsch-2.28.7.jar:."]
CMD ["MultiConn"]
