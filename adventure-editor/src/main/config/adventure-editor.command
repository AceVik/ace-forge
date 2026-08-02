#!/bin/sh
cd $(dirname "${0}")
java -Xmx8192m -Xss4m -Dfile.encoding=UTF-8 -jar $project.build.finalName$
