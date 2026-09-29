@echo off
set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.17.10-hotspot
set Path=%JAVA_HOME%\bin;%Path%
echo Switched to Java 17 for this session.
java -version
