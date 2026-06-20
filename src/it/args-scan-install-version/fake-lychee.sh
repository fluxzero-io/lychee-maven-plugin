#!/bin/sh
args_file="$(dirname "$0")/../../../lychee-args.txt"
inputs_file="$(dirname "$0")/../../../lychee-inputs.txt"
printf '%s\n' "$@" > "$args_file"
: > "$inputs_file"

previous_arg=
for arg in "$@"; do
  if [ "$previous_arg" = "--files-from" ]; then
    cat "$arg" > "$inputs_file"
  fi
  previous_arg="$arg"
done

exit 0
