#!/bin/sh
cd $(dirname "${0}")
java -Xmx32768m -Xss8m $mandatory.java.args$ -jar $project.build.finalName$
