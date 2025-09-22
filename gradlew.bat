@echo off
:: Gradle startup script for Windows
set DIR=%~dp0
java -jar "%DIR%\gradle\wrapper\gradle-wrapper.jar" %*
