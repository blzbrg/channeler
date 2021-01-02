#! /bin/sh

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

if command -v ncat 2>&1 > /dev/null
then
    # ncat ships with nmap. It is the most modern and uniform netcat-like program
    #
    # Apparently ncat on the client side doesn't close the connection when receiving EOT,
    # so --send-only is needed
    echo -en "$msg" | ncat --send-only localhost 9001
elif command -v socat 2>&1 > /dev/null
then
    # Hopefully socat is more uniform than netcat. Tested on socat 1.7.3.4 on Arch. When
    # socat sees EOF on stdin it begins to close it's end of the socket, waiting for a bit
    # for responses.
    echo -en "$msg" | socat - tcp:localhost:9001
elif command -v nc 2>&1 > /dev/null
then
    # nc is netcat. Implementations are ubiquitous, but fragmented.
    ncHelp=`nc -h 2>&1 > /dev/null`
    if echo -n "$ncHelp" | grep --quiet -- '--close' -
    then
        # --close is undocumented, but is tested in gnu-netcat 0.7.1
        # See https://sourceforge.net/p/netcat/bugs/60/
        echo -en "$msg" | nc --close localhost 9001
    elif echo -n "$ncHelp" | grep --quiet -- '-N' -
    then
        # openbsd-netcat requires -N (and also that the server send a FIN in response)
        echo -en "$msg" | nc -N localhost 9001
    else
        # last-ditch effort, use -w, which apparently exists on Windows
        echo -en "$msg" | nc -w 1 localhost 9001
    fi
fi