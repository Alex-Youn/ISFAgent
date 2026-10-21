#!/usr/bin/ksh
#
# Start dbagent-agent (ISFAgent) in the background.
#
#   ./start-agent.sh
#
# Files expected next to this script:
#   dbagent-agent.jar   agent.properties   (java8/ = bundled JVM, preferred)
# Created on first start: logs/  tmp/  work/  agent.pid
#
# Environment overrides (optional):
#   AGENT_USER  OS account allowed to run the agent       (default: sfo_user)
#   AGENT_JAVA  path of the java command to use           (default: ./java8/bin/java, then $JAVA_HOME, then PATH)
#   AGENT_XMX   max heap                                  (default: 128m)
#
# Notes
#  - ASCII only on purpose: AIX consoles are often EUC-KR and garble UTF-8 text.
#  - Plain POSIX sh / ksh88 syntax only (no [[ ]], no arrays, no $(( ))).
#  - This file must keep LF line endings (a CR makes ksh fail with "not found" errors).

DIR=$(cd "$(dirname "$0")" && pwd)
cd "$DIR" || exit 1

AGENT_USER=${AGENT_USER:-sfo_user}
AGENT_XMX=${AGENT_XMX:-128m}
JAR="$DIR/dbagent-agent.jar"
CONF="$DIR/agent.properties"
PIDFILE="$DIR/agent.pid"
OUTLOG="$DIR/logs/agent.out"

# The process is recognised by the marker -Dapp.name=dbagent-agent, which is the first argument after the java path.
# It is placed first because some ps implementations truncate long argument lists; the jar path comes much later.
is_agent_pid() {
    [ -n "$1" ] && ps -p "$1" -o args 2>/dev/null | grep 'app.name=dbagent-agent' >/dev/null 2>&1
}

# ---- who is running this -------------------------------------------------------------
if [ "$(id -u)" = "0" ]; then
    echo "ERROR: do not run the agent as root. Run it as $AGENT_USER." >&2
    exit 1
fi
ME=$(id -un)
if [ "$ME" != "$AGENT_USER" ]; then
    echo "ERROR: run this as $AGENT_USER (current user: $ME). To allow another account: AGENT_USER=$ME $0" >&2
    exit 1
fi

# ---- files -----------------------------------------------------------------------------
if [ ! -f "$JAR" ]; then
    echo "ERROR: $JAR not found" >&2
    exit 1
fi
if [ ! -f "$CONF" ]; then
    echo "ERROR: $CONF not found (copy agent.properties.sample to agent.properties and edit it)" >&2
    exit 1
fi
# The config holds the token: only the owner should be able to read it.
GO=$(ls -l "$CONF" | cut -c5-10)
if [ "$GO" != "------" ]; then
    echo "WARNING: $CONF is accessible by group/others. Run: chmod 600 $CONF" >&2
fi

# ---- already running? ----------------------------------------------------------------
if [ -f "$PIDFILE" ]; then
    OLD=$(cat "$PIDFILE")
    if is_agent_pid "$OLD"; then
        echo "Already running (pid $OLD). Use ./stop-agent.sh first."
        exit 0
    fi
    rm -f "$PIDFILE"
fi

# ---- which java ------------------------------------------------------------------------
if [ -n "$AGENT_JAVA" ]; then
    JAVA="$AGENT_JAVA"
elif [ -x "$DIR/java8/bin/java" ]; then
    JAVA="$DIR/java8/bin/java"
elif [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
    echo "WARNING: bundled java8/ not found, using JAVA_HOME ($JAVA_HOME)" >&2
else
    JAVA=java
    echo "WARNING: bundled java8/ not found, using java from PATH" >&2
fi
if ! "$JAVA" -version >/dev/null 2>&1; then
    echo "ERROR: cannot run $JAVA" >&2
    exit 1
fi

mkdir -p "$DIR/logs" "$DIR/tmp" "$DIR/work" || exit 1

# ---- start -----------------------------------------------------------------------------
echo "Starting with $JAVA ..."
nohup "$JAVA" -Dapp.name=dbagent-agent -Xmx"$AGENT_XMX" -Djava.io.tmpdir="$DIR/tmp" -jar "$JAR" --config "$CONF" >> "$OUTLOG" 2>&1 &
PID=$!
echo "$PID" > "$PIDFILE"

# ---- wait until it answers its own health check ---------------------------------------
TRY=0
while [ "$TRY" -lt 8 ]; do
    sleep 2
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "ERROR: the agent exited right after start. Last lines of $OUTLOG:" >&2
        tail -20 "$OUTLOG" >&2
        rm -f "$PIDFILE"
        exit 1
    fi
    if "$JAVA" -jar "$JAR" --config "$CONF" --health >"$DIR/tmp/health.out" 2>&1; then
        echo "Started (pid $PID). Health check:"
        cat "$DIR/tmp/health.out"
        exit 0
    fi
    TRY=$(expr "$TRY" + 1)
done
echo "WARNING: started (pid $PID) but the health check did not succeed yet. See $OUTLOG and logs/agent.0.log" >&2
echo "  HTTP 401 with agent.allowed-clients set: the check comes from this server itself (agent.bind-address," >&2
echo "  or 127.0.0.1 when bind-address=0.0.0.0) - add that address to agent.allowed-clients." >&2
cat "$DIR/tmp/health.out" >&2
exit 1
