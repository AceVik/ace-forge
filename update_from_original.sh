#!/bin/bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
cd "$DIR" || exit 1

echo "Pulling and rebasing latest changes from Card-Forge original repository (upstream master)..."
git pull --rebase upstream master
