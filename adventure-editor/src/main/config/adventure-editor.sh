#!/bin/sh
cd $(dirname "${0}")
java -Xmx32768m -Xss8m -Dfile.encoding=UTF-8 -jar $project.build.finalName$
