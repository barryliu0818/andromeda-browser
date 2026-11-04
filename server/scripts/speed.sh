i=$(ip route get 1.1.1.1 | awk '{for(n=1;n<=NF;n++) if($n=="dev") print $(n+1)}')
a=$(cat /sys/class/net/$i/statistics/rx_bytes); sleep 10; b=$(cat /sys/class/net/$i/statistics/rx_bytes)
echo "iface $i: $(( (b-a)/10/1024/1024 )) MB/s ($(( (b-a)*8/10/1000000 )) Mbps)"
echo "chromium dir: $(du -sh ~/chromium 2>/dev/null | cut -f1)"
grep '^===' ~/logs/setup.log | tail -2; tail -3 ~/logs/setup.log | cut -c1-200
