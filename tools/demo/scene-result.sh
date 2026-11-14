#!/usr/bin/env bash
# 장면 하나의 결과를 기다려 찍고, 정차 상태로 되돌린다(debug 빌드 전용).
# 사용법: 차 앱 '시연·측정'에서 장면을 고른 뒤 이 스크립트를 먼저 실행하고, '기다린다'가 찍히면 '실행하기'를 누른다.
#         SERIAL=emulator-5560 환경 변수로 기기를 바꾼다.
#
# 앱은 '실행하기' 한 번마다 끝 알림 하나를 두 곳에 남긴다: SCENARIO 로그(결과 표 포함)와 결과 폴더의 새 .done 파일(첫 줄이 같은 알림 줄).
# 끝 알림 줄은 '결과 파일 · <CSV 경로> · 요청 N회[ · 메모]' 또는 '시작하지 못함 · <까닭>'이다. 이 스크립트는 둘 중 먼저 오는 것으로 끝을 안다(한쪽이 빠져도 끝난다).
#
# 끝나는 길(종료 코드). tools/demo/selftest.py가 길마다 가짜 adb로 확인한다.
#   준비 단계
#     adb 연결 없음·Pleos 아님·앱 없음·release 빌드·앱이 떠 있지 않음      → 까닭을 찍고 1
#     결과 폴더 목록을 세 번 시도해도 읽지 못함(앞 장면 결과를 가릴 기준이 없음) → 1
#     로그에 이 실행의 표식 줄을 세 번 남겨도 읽지 못함·로그를 읽던 adb가 끊김 → 1
#   기다리는 단계(로그는 표식 뒤의 줄만, 파일은 시작 때 없던 .done만 본다. 그래서 앞 장면의 결과는 읽지 않는다)
#     '시작하지 못함'(장면 파일 오류 등)                                   → 그 줄을 찍고 1(장면이 돌지 않아 되돌리지 않는다)
#     앱이 꺼짐(시스템이 다시 띄워 pid가 바뀐 때 포함, 2초 간격 두 번 연달아) → reset.sh로 정차 상태로 되돌리고 1
#     (adb 호출 하나가 한 번 실패하면: 목록·.done·pid는 다음 번에 다시 보고, 표식은 다시 남기고, CSV는 로그의 표로 넘어간다)
#     로그를 읽던 adb가 끊김                                              → 결과 폴더(.done)만 보며 계속 기다린다(adb가 아주 끊기면 pid 확인이 실패해 앱 꺼짐 길로 간다)
#     Ctrl-C·TERM                                                         → 장면이 돌고 있으면 직접 멈추라는 안내를 찍고 130
#   결과 단계('결과 파일 · …'이면)
#     결과는 앱이 저장한 CSV에서 읽는다. 앱이 '결과 파일을 쓰지 못함'이라 했거나 파일을 못 읽으면 로그의 표를 쓴다
#     요청한 N회를 다 채우고, 표가 온전하고(요약 줄의 회차 수 = 읽은 행 수), 모든 회차가 completed=true면 통과
#     어느 쪽이든 reset.sh를 실행한다. 통과이고 reset.sh도 성공하면 0, 아니면 1
set -u
cd "$(dirname "$0")" && . ./vc.sh
RES="/data/user/10/$PKG/files/scenario-results"
app_pid() { a shell pidof "$PKG" 2>/dev/null | tr -d '\r'; }
# 결과 폴더의 .done 목록. 끝까지 읽었으면 0(폴더가 아직 없으면 빈 목록), 못 읽었으면 1(빈 목록과 구별한다)
done_files() {
  local out; out="$(dev_read "run-as $PKG --user 10 sh -c $(q "[ ! -d $RES ] || ls $RES")")" || return 1   # 폴더가 없으면 빈 목록, 있는데 ls가 실패하면 실패
  printf '%s\n' "$out" | grep '\.done$' | sort; return 0
}
app_cat() { dev_read "run-as $PKG --user 10 cat $(q "$1")"; }   # 앱 파일 하나를 끝까지 읽었을 때만 0

# ── 준비 ─────────────────────────────────────────────────────────────────────
need_pleos
need_debug_app "장면(시연·측정)"
pid0="$(app_pid)"   # 장면을 돌릴 앱 프로세스. 꺼졌다가 시스템이 다시 띄우면(예약 작업 등) pid가 바뀐다
[ -n "$pid0" ] || die "앱이 떠 있지 않다(차 앱 '시연·측정' 화면을 연 뒤 실행)"
# 시작 때 이미 있던 끝 알림 파일(앞 장면 것). 목록을 못 읽은 채 빈 목록으로 두면 앞 장면 것을 새 결과로 읽으므로, 읽을 때까지 세 번 시도하고 안 되면 멈춘다
got=0
for _ in 1 2 3; do if done0="$(done_files)"; then got=1; break; fi; sleep 1; done
[ $got = 1 ] || die "결과 폴더 목록을 읽지 못했다(세 번 시도, adb 연결 확인)"

since="$(a shell date +%s | tr -d '\r').000"   # 로그 버퍼를 처음부터 읽지 않게 지금부터만 받는다(어느 줄이 이 실행 것인지는 아래 표식으로 가린다)
log="$(mktemp)"
adb -s "$SERIAL" logcat -v raw -T "$since" -s SCENARIO:I > "$log" 2>/dev/null &   # 함수 a가 아니라 adb를 직접 띄워 $!로 끌 수 있게
lp=$!
stop_log() { kill $lp 2>/dev/null; wait $lp 2>/dev/null; }   # wait로 거둬 셸의 'Terminated' 알림이 찍히지 않게
trap 'stop_log; rm -f "$log" "$log.cut" "$log.ok" "$log.csv"' EXIT
trap 'echo "멈춤: 장면이 돌고 있으면 앱에서 \"멈추기\"를 누른 뒤 reset.sh로 정차 상태로 되돌린다" >&2; exit 130' INT TERM

# 이 실행만의 표식 줄을 로그에 남기고, 그 뒤의 줄만 본다. 표식 한 줄이 로그에 들어가지 않은 일이 있어(에뮬레이터에서 한 번 있었고 원인은 미판정) 새 표식으로 세 번까지 다시 남긴다
mark=""
for _ in 1 2 3; do
  m="scene-result-start-$$-$RANDOM"
  if a shell log -p i -t SCENARIO "$m"; then
    for _ in 1 2 3 4 5 6; do
      grep -q "^$m" "$log" && { mark="$m"; break 2; }
      kill -0 $lp 2>/dev/null || die "앱 로그를 읽지 못했다(adb 연결 확인)"
      sleep 0.5
    done
  fi
  echo "표식 줄($m)이 로그에 보이지 않아 다시 남긴다" >&2
done
[ -n "$mark" ] || die "앱 로그에 표식 줄을 남기거나 읽지 못했다(세 번 시도, adb 연결 확인)"
cut_log() { sed -n "/^$mark/,\$p" "$log" > "$log.cut"; }   # 표식 줄부터 끝까지

# ── 기다리기: 로그의 끝 알림 줄 또는 새 .done 파일 ───────────────────────────
echo "기다린다. 지금 차 앱 '시연·측정'에서 '실행하기'를 누른다(장면이 끝나면 앱이 결과 표를 남긴다. 멈추려면 Ctrl-C)..."
end_line=""; miss=0; log_ok=1
while :; do
  if [ $log_ok = 1 ]; then
    cut_log
    end_line="$(grep -m1 -e '^결과 파일 ·' -e '^시작하지 못함' "$log.cut")"
    [ -n "$end_line" ] && break
    if ! kill -0 $lp 2>/dev/null; then   # 로그를 읽던 adb가 끊겼다. 장면은 앱에서 계속 도니 결과 폴더(.done)만 보며 계속 기다린다
      log_ok=0; echo "앱 로그 연결이 끊겨 결과 폴더의 끝 알림 파일만 보며 기다린다" >&2
    fi
  fi
  new_done=""
  if now="$(done_files)"; then new_done="$(comm -13 <(printf '%s\n' "$done0") <(printf '%s\n' "$now") | grep . | head -1)"; fi   # 못 읽으면 다음 번에 다시 본다
  if [ -n "$new_done" ]; then
    end_line="$(app_cat "$RES/$new_done" | head -1)"
    [ -n "$end_line" ] && { echo "앱 로그에 끝 알림이 없어 결과 폴더의 ${new_done}으로 끝을 안다" >&2; break; }
  fi
  if [ "$(app_pid)" != "$pid0" ]; then miss=$((miss + 1)); else miss=0; fi
  if [ $miss -ge 2 ]; then   # 두 번 연달아 다르면 꺼졌거나 다른 프로세스로 다시 떴다(pid 확인 한 번 실패로는 끝내지 않는다)
    echo "실패: 앱이 꺼져 장면 결과가 나오지 않는다. 장면이 바꿨을 수 있는 차 상태를 정차로 되돌린다" >&2
    ./reset.sh; exit 1
  fi
  sleep 2
done
sleep 1   # 로그의 결과 덩이(여러 줄)가 다 들어오게
stop_log
cut_log; mv "$log.cut" "$log"   # 이 뒤로는 표식 뒤의 줄만 쓴다
case "$end_line" in 시작하지\ 못함*) echo "$end_line"; exit 1 ;; esac

# ── 결과 ─────────────────────────────────────────────────────────────────────
csv_path="${end_line#결과 파일 · }"; csv_path="${csv_path%% · *}"
: > "$log.csv"
case "$end_line" in *'결과 파일을 쓰지 못함'*) ;; *) app_cat "$csv_path" > "$log.csv" || : > "$log.csv" ;; esac
python3 - "$log" "$log.csv" "$end_line" <<'PY'
import re, sys
log, csv_file, note = sys.argv[1:4]
lines = [l.rstrip("\r\n") for l in open(log, encoding="utf-8", errors="replace")]
starts = [i for i, l in enumerate(lines) if l.startswith("결과 파일 ·")]
log_block = lines[starts[-1] + 1:] if starts else []   # 로그의 결과 표(로그에 끝 알림이 없으면 비어 있다)
csv_block = [l.rstrip("\r\n") for l in open(csv_file, encoding="utf-8", errors="replace")]

def table(block):
    """결과 표 하나를 읽는다. whole = 머리·요약이 있고, 요약의 회차 수 = 읽은 행 수이고, 모든 행의 칸 수가 머리와 같다(중간에 끊긴 표가 아니다)."""
    head = next((l for l in block if l.startswith("run,")), "").split(",")
    col = {name: i for i, name in enumerate(head)}
    c = col.get("completed")
    rows = [l.split(",") for l in block if l[:1].isdigit()] if c is not None else []
    summary = next((l for l in block if l.startswith("summary,")), "").split(",")
    whole = (c is not None and bool(rows) and len(summary) > c and summary[c].endswith(f"/{len(rows)}")
             and all(len(r) == len(head) for r in rows))
    return col, c, rows, whole

# 온전한 표를 고른다: 저장된 CSV가 먼저, 그다음 로그의 표. 둘 다 온전하지 않으면 있는 쪽으로 찍고 실패로 친다
csv_t, log_t = table(csv_block), table(log_block)
from_file = csv_t[3] or not log_t[3] and bool(csv_t[2])
col, c, rows, whole = csv_t if from_file else log_t
passed = sum(1 for r in rows if len(r) > c and r[c] == "true") if c is not None else 0
m = re.search(r" · 요청 (\d+)회", note)
asked = int(m.group(1)) if m else None
print(note)
if not from_file:
    print("결과 파일을 읽지 못해 앱 로그의 표를 쓴다" if not csv_block else "결과 파일이 온전하지 않아 앱 로그의 표를 쓴다")
if not rows:
    print("끝난 회차 없음")
elif not whole:
    print("결과 표가 잘려 회차를 다 읽지 못함(실패로 친다)")
elif asked != len(rows):
    print(f"요청한 {asked if asked is not None else '?'}회 중 {len(rows)}회만 끝남(멈춤 등, 실패로 친다)")
for r in rows:
    done = len(r) > c and r[c] == "true"
    def cell(name): return r[col[name]] if name in col and col[name] < len(r) else ""
    print(f"회차 {r[0]}: 기대 조건 {'충족' if done else '못 채움'}, 차량 제어 반영률 {cell('control_ok_rate') or '제어 없음'}"
          + ("" if done else f", 처음 어긋난 장면 시각 {cell('failed_step') or '?'}초"))
print(f"통과 {passed}/{asked if asked is not None else '?'}")
ok = whole and asked == len(rows) and passed == len(rows)
open(log + ".ok", "w").write("1" if ok else "0")
PY
ok="$(cat "$log.ok" 2>/dev/null)"
./reset.sh || exit 1   # 정차로 되돌리지 못하면 실패로 끝낸다
[ "$ok" = 1 ]
