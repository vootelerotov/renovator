# Renovator

## Description

Renovator helps to keep your dependency update PR-s under control.

If you use Renovate or Dependebot, you might have noticed that they create a lot of PR-s.

Renovator helps to keep them under control by merging them semi-automatically.

## How it works

A CLI tool that you can run, that lists all the open PR-s in your repositories, and you can select which ones to merge.

## Usage

```bash
./gradlew installDist
./build/install/renovator/bin/renovator -h
```