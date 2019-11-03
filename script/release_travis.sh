#!/usr/bin/env bash
if [ -z "$TRAVIS_BRANCH" ]; then
  echo "TRAVIS_BRANCH environment variable must be set"
  exit 1
fi
git config --global user.name "Deploy CI"
git checkout "$TRAVIS_BRANCH"
sbt "release skip-tests with-defaults"