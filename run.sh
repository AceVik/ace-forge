#!/bin/bash

# Find directory where run.sh is located
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
cd "$DIR" || exit 1

JAR_PATH="forge-gui-mobile-dev/target/forge-gui-mobile-dev-2.0.14-SNAPSHOT-jar-with-dependencies.jar"
REBUILD=false
FORCE=false
SET_LANG=""
DEBUG=false
CHEATS=false
PREFS_FILE="$HOME/Library/Application Support/Forge/preferences/forge.preferences"

# Function to update key-value in properties file
update_pref() {
    local key="$1"
    local value="$2"
    local file="$3"
    if [ -f "$file" ]; then
        python3 -c "
import sys
content = open(sys.argv[3]).read()
lines = content.splitlines()
found = False
for i, line in enumerate(lines):
    if line.startswith(sys.argv[1] + '='):
        lines[i] = sys.argv[1] + '=' + sys.argv[2]
        found = True
if not found:
    lines.append(sys.argv[1] + '=' + sys.argv[2])
open(sys.argv[3], 'w').write('\n'.join(lines) + '\n')
" "$key" "$value" "$file"
    fi
}

# Check for arguments
for arg in "$@"; do
    case "$arg" in
        --force|-f)
            FORCE=true
            ;;
        --english|-en|-e)
            SET_LANG="en-US"
            ;;
        --german|-de|-g)
            SET_LANG="de-DE"
            ;;
        --debug|-d)
            DEBUG=true
            ;;
        --cheats|-c)
            CHEATS=true
            ;;
    esac
done

# Apply preferences changes if file exists
if [ -f "$PREFS_FILE" ]; then
    # Apply language change if requested
    if [ -n "$SET_LANG" ]; then
        echo "Setting language to $SET_LANG in $PREFS_FILE..."
        update_pref "UI_LANGUAGE" "$SET_LANG" "$PREFS_FILE"
    fi

    # Toggle DEV_MODE_ENABLED based on debug flag
    if [ "$DEBUG" = true ]; then
        echo "Enabling developer mode (DEV_MODE_ENABLED=true) in $PREFS_FILE..."
        update_pref "DEV_MODE_ENABLED" "true" "$PREFS_FILE"
    else
        update_pref "DEV_MODE_ENABLED" "false" "$PREFS_FILE"
    fi

    # Toggle CHEATS_ENABLED based on cheats flag
    if [ "$CHEATS" = true ]; then
        echo "Enabling cheats (CHEATS_ENABLED=true) in $PREFS_FILE..."
        update_pref "CHEATS_ENABLED" "true" "$PREFS_FILE"
    else
        update_pref "CHEATS_ENABLED" "false" "$PREFS_FILE"
    fi
else
    echo "Warning: Preferences file not found at: $PREFS_FILE"
    echo "You may need to run the game once first so it creates the preferences file."
fi

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

# Set Java options
JAVA_OPTS=("-Xmx4096m")
if [ "$DEBUG" = true ]; then
    echo "Starting in DEBUG mode (JDWP agent listening on 127.0.0.1:5005)..."
    JAVA_OPTS+=("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005")
fi

JAVA_OPTS+=(
  "--add-opens" "java.desktop/java.beans=ALL-UNNAMED"
  "--add-opens" "java.desktop/javax.swing.border=ALL-UNNAMED"
  "--add-opens" "java.desktop/javax.swing.event=ALL-UNNAMED"
  "--add-opens" "java.desktop/sun.swing=ALL-UNNAMED"
  "--add-opens" "java.desktop/java.awt.image=ALL-UNNAMED"
  "--add-opens" "java.desktop/java.awt.color=ALL-UNNAMED"
  "--add-opens" "java.desktop/sun.awt.image=ALL-UNNAMED"
  "--add-opens" "java.desktop/javax.swing=ALL-UNNAMED"
  "--add-opens" "java.desktop/java.awt=ALL-UNNAMED"
  "--add-opens" "java.base/java.util=ALL-UNNAMED"
  "--add-opens" "java.base/java.lang=ALL-UNNAMED"
  "--add-opens" "java.base/java.lang.reflect=ALL-UNNAMED"
  "--add-opens" "java.base/java.text=ALL-UNNAMED"
  "--add-opens" "java.desktop/java.awt.font=ALL-UNNAMED"
  "--add-opens" "java.base/jdk.internal.misc=ALL-UNNAMED"
  "--add-opens" "java.base/sun.nio.ch=ALL-UNNAMED"
  "--add-opens" "java.base/java.nio=ALL-UNNAMED"
  "--add-opens" "java.base/java.math=ALL-UNNAMED"
  "--add-opens" "java.base/java.util.concurrent=ALL-UNNAMED"
  "--add-opens" "java.base/java.net=ALL-UNNAMED"
  "-Dio.netty.tryReflectionSetAccessible=true"
  "-Dfile.encoding=UTF-8"
)

# Filter out our control flags before launching
ARGS=()
for arg in "$@"; do
    case "$arg" in
        --force|-f|--english|-en|-e|--german|-de|-g|--debug|-d|--cheats|-c)
            ;;
        *)
            ARGS+=("$arg")
            ;;
    esac
done

cd "$DIR/forge-gui-mobile-dev" || exit 1
export JAVA_HOME=/Users/viktor/.jenv/versions/17
exec "$JAVA_HOME/bin/java" "${JAVA_OPTS[@]}" -jar target/forge-gui-mobile-dev-2.0.14-SNAPSHOT-jar-with-dependencies.jar "${ARGS[@]}"
