#!/system/bin/sh
# 以应用自身 uid 结束应用进程（比 am force-stop 温和：不会把应用标记为 stopped，
# 因此系统不会顺带停用它的无障碍服务）。
P=$(pidof com.dsh.screenocr)
echo "pid=$P"
if [ -n "$P" ]; then
  kill -9 $P
  echo "kill_rc=$?"
else
  echo "no_process"
fi
sleep 1
echo "after=$(pidof com.dsh.screenocr)"
