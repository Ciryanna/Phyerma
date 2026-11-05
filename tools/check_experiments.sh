#!/bin/sh
# Check experiment files with the same parser the app uses to open them:
#
#     tools/check_experiments.sh my.phyphox some/folder ...
#
# Every .phyphox file (and any file named directly) is loaded on a simulated phone that has every
# sensor, so only real mistakes fail: wrong or missing elements, undeclared containers, a file
# version newer than the app supports. Each file prints OK or the parser's own message.
#
# Without arguments it checks the experiments shipped in the app (assets/experiments and
# assets/ai/examples). Needs JAVA_HOME (Android Studio's jbr works) and takes a minute the first time.
set -u
root=$(cd "$(dirname "$0")/.." && pwd)

paths=""
for p in "$@"; do
    abs=$(cd "$(dirname "$p")" 2>/dev/null && pwd)/$(basename "$p")
    if command -v cygpath >/dev/null 2>&1; then abs=$(cygpath -w "$abs"); fi
    if [ -z "$paths" ]; then paths=$abs; else paths="$paths;$abs"; fi
done

cd "$root" || exit 2
if [ -n "$paths" ]; then
    PHYERMA_CHECK=$paths ./gradlew :app:cleanTestRegularDebugUnitTest :app:testRegularDebugUnitTest --tests '*ExperimentFilesCheckTest' -q
    rc=$?
    echo; cat app/build/experiment-check-given.txt 2>/dev/null
else
    ./gradlew :app:cleanTestRegularDebugUnitTest :app:testRegularDebugUnitTest --tests '*ExperimentFilesCheckTest.bundledExperimentsLoad' -q
    rc=$?
    echo; cat app/build/experiment-check-bundled.txt 2>/dev/null
fi
exit $rc
