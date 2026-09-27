#!/bin/sh
set -eu

terms_version="$(sed -n 's/^basetool\.terms\.version=//p' /seed/terms-version.properties | tr -d '[:space:]')"
if [ -z "$terms_version" ]; then
  echo "sandbox-seed: no basetool.terms.version in /seed/terms-version.properties" >&2
  exit 1
fi

psql --no-psqlrc --single-transaction -v ON_ERROR_STOP=1 -v terms_version="$terms_version" \
  -f /seed/seed.sql
echo "sandbox-seed: applied, Terms of Use version $terms_version accepted for the sandbox accounts"
