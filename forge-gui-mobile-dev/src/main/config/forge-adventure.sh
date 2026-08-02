#!/bin/sh
cd $(dirname "${0}")
java -Xmx8192m -Xss4m $mandatory.java.args$ -jar $project.build.finalName$
