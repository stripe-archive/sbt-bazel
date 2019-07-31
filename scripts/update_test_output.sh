#!/bin/bash
set -euxo pipefail

BASEDIR=$(pwd)
PLUGIN_VERSION=$(sbt -no-colors 'show version' | tail -n1 | cut -f2 | tr -d '\n')

if [[ $PLUGIN_VERSION != *-SNAPSHOT ]]; then
    echo "You must update version.sbt to use a version ending in -SNAPSHOT before running this script"
    exit 1
fi

sbt publishLocal

for TESTDIR in plugin/src/sbt-test/sbt-bazel/* ; do
    TMPDIR=$(mktemp -d)
    cp -R "$TESTDIR/" "$TMPDIR"
    pushd "$TMPDIR"
    nix-shell --command "sbt -Dplugin.version=$PLUGIN_VERSION bazelGenerate" "$BASEDIR/shell.nix"
    if [ -f WORKSPACE ]; then
         mv WORKSPACE "$BASEDIR/$TESTDIR/WORKSPACE.expect"
    fi

    for OUTFILE in {WORKSPACE,BUILD,*/BUILD}; do
        if [ -f "$OUTFILE" ]; then
            EXPECT_NAME=$(echo $OUTFILE.expect | tr '/' '-')
            EXPECT_PATH="$BASEDIR/$TESTDIR/$EXPECT_NAME"
            if [ -f "$EXPECT_PATH" ]; then
                mv "$OUTFILE" "$EXPECT_PATH"
            fi
        fi
    done

    rm -r "$TMPDIR"

    popd
done
