#!/usr/bin/ksh
#
# Stop dbagent-agent (ISFAgent) started by start-agent.sh.
#
#   ./stop-agent.sh
#
# Sends SIGTERM (the agent shuts down cleanly), waits up to 10 seconds, then SIGKILL.
# ASCII only, plain POSIX sh / ksh88 syntax, LF line endings - see start-agent.sh.

DIR=$(cd "$(dirname "$0")" && pwd)
PIDFILE="$DIR/agent.pid"

# Same check as start-agent.sh (marker -Dapp.name=dbagent-agent): a reused pid of another process is never killed.
is_agent_pid() {
    [ -n "$1" ] && ps -p "$1" -o args 2>/dev/null | grep 'app.name=dbagent-agent' >/dev/null 2>&1
}

if [ ! -f "$PIDFILE" ]; then
    echo "Not running (no $PIDFILE)."
    exit 0
fi
PID=$(cat "$PIDFILE")
if ! is_agent_pid "$PID"; then
    echo "Not running (pid $PID is gone or is not the agent). Removing stale $PIDFILE."
    rm -f "$PIDFILE"
    exit 0
fi

kill "$PID" 2>/dev/null
TRY=0
while [ "$TRY" -lt 10 ]; do
    if ! kill -0 "$PID" 2>/dev/null; then
        rm -f "$PIDFILE"
        echo "Stopped (pid $PID)."
        exit 0
    fi
    sleep 1
    TRY=$(expr "$TRY" + 1)
done
echo "Did not stop within 10 seconds, sending SIGKILL to pid $PID."
kill -9 "$PID" 2>/dev/null
sleep 1
rm -f "$PIDFILE"
echo "Killed (pid $PID)."
