#!/bin/sh
set -eu
git config --local core.hooksPath .githooks
git config --local commit.template .gitmessage
printf '%s\n' 'Enabled index hygiene and Conventional Commit hooks for this clone.'
