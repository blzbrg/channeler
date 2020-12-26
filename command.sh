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
msg=`printf "%s\x04" "$msg"`

if command -v ncat 2>&1 > /dev/null
then
    # ncat ships with nmap. It is the most modern and uniform netcat-like program
    #
    # Apparently ncat on the client side doesn't close the connection when receiving EOT,
    # so --send-only is needed
    printf "$msg" | ncat --send-only localhost 9001
fi