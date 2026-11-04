#!/system/bin/sh
# ClusterHUD shell 看门狗（UID 2000 常驻）
#
# 为什么必须有这一层：
#   DiLink 4.0 熄火是「整机 suspend」不是断电，唤醒时不发任何广播；ROM 又会按 UID
#   整组清理应用（连 AlarmManager 闹钟一起端掉）。应用内的自愈层在那一刻全部失效。
#   本脚本跑在 shell，只被 suspend 冻结、不被按 UID 清理，唤醒后 sleep 到期立刻巡检。
#
# 用法： sh app-watchdog.sh loop   # 常驻（单例）
#        sh app-watchdog.sh once   # 单次检查
# 日志： /data/local/tmp/clusterlab-watchdog.log   单例： .pid

PKG=com.byd.clusterlab
ACT=$PKG/.CardActivity
BOOTRECV=$PKG/.BootReceiver
TICK=com.byd.clusterlab.TICK
LOG=/data/local/tmp/clusterlab-watchdog.log
PIDFILE=/data/local/tmp/clusterlab-watchdog.pid
HALT=/sdcard/clusterlab_halt

log() { echo "$(date +%m-%d_%H:%M:%S) $*" >> "$LOG" 2>/dev/null; }

trimlog() {
  [ -f "$LOG" ] || return 0
  sz=$(wc -c < "$LOG" 2>/dev/null | tr -d " ")
  if [ -n "$sz" ] && [ "$sz" -gt 65536 ]; then
    tail -n 100 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
  fi
}

alive() {
  pidof "$PKG" >/dev/null 2>&1 && return 0
  ps -A 2>/dev/null | grep -q "[c]om.byd.clusterlab"
}

# 屏幕是否点亮。熄火停车时屏幕是黑的，这时拉界面纯属白烧电。
awake() {
  local s
  s=$(dumpsys power 2>/dev/null | grep -m1 -E "mWakefulness=")
  case "$s" in
    *Awake*) return 0 ;;
    "") return 0 ;;   # 读不到就别拦着，宁可多试一次
    *) return 1 ;;
  esac
}

# 面板点了「停止卡片」就不要再拉起，尊重用户意图。
halted() {
  [ -f "$HALT" ] && return 0
  return 1
}

# 卡片是否真的可见（不是「有 ActivityRecord」，是窗口 isVisible=true）
visible() {
  n=$(dumpsys window windows 2>/dev/null \
      | awk '/Window #/ {w=$0} /isVisible=true/ && w ~ /clusterlab/ {c++} END{print c+0}')
  [ "${n:-0}" -gt 0 ] 2>/dev/null
}

ensure_card() {
  halted && return 0
  awake || return 0
  # 进程不在：显式广播带 -f 0x20（shell 默认 EXCLUDE_STOPPED_PACKAGES，包在 stopped 态时一条都收不到）。
  # 绝不 am start 主界面，那会弹窗抢焦点、把用户顶出导航。
  if ! alive; then
    log "进程不在，发 TICK 拉起"
    am broadcast -a "$TICK" -n "$BOOTRECV" -f 0x20 >/dev/null 2>&1
    sleep 2
    alive || log "TICK 无效，等下一轮"
    return 0
  fi
  visible && return 0
  log "卡片不可见，am start --display 1"
  am start-activity --display 1 -n "$ACT" >/dev/null 2>&1
}

if [ "$1" = "loop" ]; then
  if [ -f "$PIDFILE" ]; then
    old=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$old" ] && kill -0 "$old" 2>/dev/null && grep -q clusterlab-watchdog /proc/$old/cmdline 2>/dev/null; then
      log "已有看门狗 pid=$old，本实例退出"; exit 0
    fi
  fi
  echo $$ > "$PIDFILE"
  trap 'rm -f $PIDFILE 2>/dev/null' EXIT INT TERM
  trimlog
  log "===== LOOP START pid=$$ ====="
  ensure_card
  while true; do
    sleep 5
    trimlog
    ensure_card
  done
elif [ "$1" = "once" ] || [ -z "$1" ]; then
  ensure_card
fi
