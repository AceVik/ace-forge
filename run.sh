#!/bin/bash

# Find directory where run.sh is located
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
cd "$DIR" || exit 1

JAR_PATH="forge-gui-mobile-dev/target/forge-gui-mobile-dev-2.0.14-SNAPSHOT-jar-with-dependencies.jar"
REBUILD=false
FORCE=false

# Check for --force or -f argument
for arg in "$@"; do
    if [ "$arg" = "--force" ] || [ "$arg" = "-f" ]; then
        FORCE=true
    fi
done

if [ "$FORCE" = true ]; then
    REBUILD=true
    echo "Force rebuild requested."
elif [ ! -f "$JAR_PATH" ]; then
    REBUILD=true
    echo "Binary not found. Building project..."
else
    # Find if any source file (.java, pom.xml, .properties, .xml) is newer than the jar
    # (ignoring target/ directories)
    NEWER_FILE=$(find . -type f \( -name "*.java" -o -name "pom.xml" -o -name "*.properties" -o -name "*.xml" \) -not -path "*/target/*" -newer "$JAR_PATH" -print -quit)
    if [ -n "$NEWER_FILE" ]; then
        REBUILD=true
        echo "Source file changed: $NEWER_FILE"
        echo "Rebuilding project..."
    fi
fi

if [ "$REBUILD" = true ]; then
    export JAVA_HOME=/Users/viktor/.jenv/versions/17
    mvn package -pl forge-gui-mobile-dev -am -DskipTests
    if [ $? -ne 0 ]; then
        echo "Error: Build failed. Exiting."
        exit 1
    fi
fi

# Filter out the --force or -f flags from the arguments passed to Java
ARGS=()
for arg in "$@"; do
    if [ "$arg" != "--force" ] && [ "$arg" != "-f" ]; then
        ARGS+=("$arg")
    fi
done

cd "$DIR/forge-gui-mobile-dev" || exit 1
export JAVA_HOME=/Users/viktor/.jenv/versions/17
exec "$JAVA_HOME/bin/java" -Xmx4096m \
  --add-opens java.desktop/java.beans=ALL-UNNAMED \
  --add-opens java.desktop/javax.swing.border=ALL-UNNAMED \
  --add-opens java.desktop/javax.swing.event=ALL-UNNAMED \
  --add-opens java.desktop/sun.swing=ALL-UNNAMED \
  --add-opens java.desktop/java.awt.image=ALL-UNNAMED \
  --add-opens java.desktop/java.awt.color=ALL-UNNAMED \
  --add-opens java.desktop/sun.awt.image=ALL-UNNAMED \
  --add-opens java.desktop/javax.swing=ALL-UNNAMED \
  --add-opens java.desktop/java.awt=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens java.base/java.text=ALL-UNNAMED \
  --add-opens java.desktop/java.awt.font=ALL-UNNAMED \
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/java.math=ALL-UNNAMED \
  --add-opens java.base/java.util.concurrent=ALL-UNNAMED \
  --add-opens java.base/java.net=ALL-UNNAMED \
  -Dio.netty.tryReflectionSetAccessible=true \
  -Dfile.encoding=UTF-8 \
  -jar target/forge-gui-mobile-dev-2.0.14-SNAPSHOT-jar-with-dependencies.jar "${ARGS[@]}"
