@echo off
setlocal

set APP_HOME=%~dp0

if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
  ) else (
    set "JAVA_CMD=java"
  )
) else (
  set "JAVA_CMD=java"
)

"%JAVA_CMD%" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar;%APP_HOME%gradle\wrapper\gradle-wrapper-shared.jar;%APP_HOME%gradle\wrapper\gradle-cli.jar;%APP_HOME%gradle\wrapper\gradle-files.jar" org.gradle.wrapper.GradleWrapperMain %*

endlocal
