#! /bin/sh

# Connect to a channeler remote-control server on localhost

# Uses TCP over port CHANNELER_PORT. One suggestion to customize this is writing a shell
# script which contains the single line
# CHANNELER_PORT=9000 ./command.sh $@

# Attempts to guess which TCP-from-the-shell command to use, but this is not totally
# reliable. If it is not working, you can override it by exporting the env var
# CHANNELER_NET_CMD. As above, a script can easily be written to store this customization.

# take the first arg to avoid having a linebreak before it
msg="$1"
shift

# concat remainder of args, with linebreak between
for arg in $@
do
    msg=$msg"\n"$arg
done

# two linebreaks at the end
msg=$msg"\n\n"

# add EOT (^D)
eot=`printf "\x04"`
msg=$msg$eot


if [ -n "$CHANNELER_PORT" ]
then
    port="$CHANNELER_PORT"
else
    port="9001" # default port
fi

if [ -n "$CHANNELER_NET_CMD" ]
then
    out="$CHANNELER_NET_CMD"
elif command -v ncat 2>&1 > /dev/null
then
    # ncat ships with nmap. It is the most modern and uniform netcat-like program
    #
    # Apparently ncat on the client side doesn't close the connection when receiving EOT,
    # so --send-only is needed
    out="ncat --send-only localhost $port"
elif command -v socat 2>&1 > /dev/null
then
    # Hopefully socat is more uniform than netcat. Tested on socat 1.7.3.4 on Arch. When
    # socat sees EOF on stdin it begins to close it's end of the socket, waiting for a bit
    # for responses.
    out="socat - tcp:localhost:$port"
elif command -v nc 2>&1 > /dev/null
then
    # nc is netcat. Implementations are ubiquitous, but fragmented.
    ncHelp=`nc -h 2>&1 > /dev/null`
    if echo -n "$ncHelp" | grep --quiet -- '--close' -
    then
        # --close is undocumented, but is tested in gnu-netcat 0.7.1
        # See https://sourceforge.net/p/netcat/bugs/60/
        out="nc --close localhost $port"
    elif echo -n "$ncHelp" | grep --quiet -- '-N' -
    then
        # openbsd-netcat requires -N (and also that the server send a FIN in response)
        out="nc -N localhost $port"
    else
        # last-ditch effort, use -w, which apparently exists on Windows
        out="nc -w 1 localhost $port"
    fi
fi

echo -en "$msg" | eval "$out"