#!/usr/bin/env sh

DIR="$( cd "$( dirname "$0" )" && pwd )"

if [ -n "$JAVA_HOME" ] ; then
    JAVA_EXEC="$JAVA_HOME/bin/java"
else
    JAVA_EXEC="java"
fi

exec "$JAVA_EXEC" -Xmx64m -Xms64m -cp "$DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
