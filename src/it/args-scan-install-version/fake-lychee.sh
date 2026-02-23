#!/bin/sh
printf '%s\n' "$@" > "$(dirname "$0")/../../../lychee-args.txt"
exit 0
