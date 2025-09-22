@echo off
set DIR=%~dp0
java -Xmx64m -Xms64m -cp "%DIR%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
