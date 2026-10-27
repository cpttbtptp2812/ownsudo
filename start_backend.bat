@echo off
set JAVA_HOME=C:\Program Files\Java\jdk-21
set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8
"D:\apache-maven-3.9.14\apache-maven-3.9.14\bin\mvn.cmd" -f "E:\owner\ownsudo\pom.xml" spring-boot:run > "E:\owner\ownsudo\backend.log" 2>&1

