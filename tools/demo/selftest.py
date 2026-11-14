#!/usr/bin/env python3
"""시연 스크립트 자체 시험.

가짜 adb·curl로 기기 없이 scene-result.sh·drive.sh·reset.sh를 돌린다. 끝나는 길마다 다음을 확인한다.
- 종료 코드와 출력
- 차량에 보낸 Virtual Console 요청
사용법: python3 tools/demo/selftest.py   (tools/check.sh가 함께 돌린다)
"""
import os
import signal
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))

# 가짜 adb: 상태 폴더($FAKE_ST)의 파일로 기기를 흉내 낸다.
#   installed·debug 파일 = 앱 설치·debug 빌드, alive 파일 = 앱 프로세스(내용이 pid)
#   before = 표식 앞에 이미 있던 로그, stream = 그 뒤에 들어오는 로그(시험이 앱 대신 덧붙인다)
#   pidof_fail·ls_fail·hang_pidof·hang_ls·partial_cat = 그 adb 호출을 실패시키거나 멈추게 하거나 출력을 끊을 횟수, drop = 남기지 않고 버릴 표식 수, files/ = 앱의 결과 폴더(CSV·.done), logcat_dead = logcat이 끊김, vc_down = VC 연결이 끊김(adb forward로 다시 붙음)
FAKE_ADB = r'''#!/bin/bash
ST="$FAKE_ST"
[ "$1" = -s ] && shift 2
take() { local n; n=$(cat "$ST/$1" 2>/dev/null || echo 0); [ "$n" -gt 0 ] && { echo $((n - 1)) > "$ST/$1"; return 0; }; return 1; }   # 실패 넣을 횟수를 하나 쓴다
end() { case "$*" in *__end__*) echo __end__ ;; esac; }   # dev_read가 붙인 '&& echo __end__': 명령이 성공했을 때만 찍는다
cmd="$*"
case "$cmd" in
  get-state) echo device ;;
  *getprop*) echo "Pleos/fake" ;;
  *"pm list packages"*) [ -f "$ST/installed" ] && echo "package:kr.aigyeot.app"; end "$cmd" ;;
  *"dumpsys package"*) [ -f "$ST/debug" ] && echo "pkgFlags=[ DEBUGGABLE ]"; end "$cmd" ;;
  *"date +%s"*) echo 100 ;;
  *pidof*) take hang_pidof && exec sleep 120   # 진짜 adb처럼 프로세스 하나가 멈춤(시간 제한이 이 프로세스를 끊는다)
           take pidof_fail && exit 1
           if [ -f "$ST/alive" ]; then cat "$ST/alive"; else exit 1; fi ;;
  *run-as*" cat "*)
    p=$(printf '%s' "$cmd" | sed -n "s/.* cat '\(.*\)' && echo __end__$/\1/p" | sed "s/'\\\\''/'/g")
    f="$ST/files/$(basename "$p")"
    [ -f "$f" ] || { echo "run-as: $p: No such file or directory"; exit 1; }
    if take partial_cat; then head -1 "$f"; exit 0; fi   # 출력이 중간에 끊김(표식 없음)
    cat "$f"; end "$cmd" ;;
  *run-as*ls*) take hang_ls && exec sleep 120
               take ls_fail && exit 1
               ls "$ST/files" 2>/dev/null; end "$cmd" ;;
  *"forward --remove"*) touch "$ST/vc_down" ;;
  *forward*) rm -f "$ST/vc_down" ;;
  "shell log -p i -t SCENARIO "*)
    take drop || echo "${@: -1}" >> "$ST/stream" ;;
  logcat*)
    [ -f "$ST/before" ] && cat "$ST/before"
    n=0
    while :; do
      [ -f "$ST/logcat_dead" ] && exit 1
      t=$(wc -l < "$ST/stream" | tr -d ' ')
      if [ "$t" -gt "$n" ]; then sed -n "$((n + 1)),${t}p" "$ST/stream"; n=$t; fi
      sleep 0.1
    done ;;
  *"am start"*) echo "Starting: Intent" ;;
  *) : ;;
esac
'''

# 가짜 curl: 요청 주소를 req에 적는다. curl_fail(정규식)에 맞는 요청은 HTTP 오류로 끝낸다.
# fail_after_drive가 있으면 기어 D 요청 뒤의 요청만, curl_fail_n이 있으면 처음 N번만 실패시킨다
FAKE_CURL = r'''#!/bin/bash
ST="$FAKE_ST"
for x in "$@"; do case "$x" in http*) url="$x" ;; esac; done
echo "$url" >> "$ST/req"
[ -f "$ST/vc_down" ] && exit 7   # 연결 끊김
if [ -f "$ST/curl_fail" ] && echo "$url" | grep -qE "$(cat "$ST/curl_fail")"; then
  if [ ! -f "$ST/fail_after_drive" ] || grep -q 'gear=Drive' "$ST/req"; then
    if [ -f "$ST/curl_fail_n" ]; then   # 처음 N번만 실패
      n=$(cat "$ST/curl_fail_n"); [ "$n" -gt 0 ] && { echo $((n - 1)) > "$ST/curl_fail_n"; exit 22; }
    else exit 22; fi
  fi
fi
echo '{"emulator_active":true,"power_on":true}'
'''

HEAD = ("run,seed,completed,failed_step,alarm_latency_ms,alarm_to_play_ms,routed_device,fallback,max_attempts,"
        "max_episode_play_ms,session_play_ms,gain_violations,lufs_violations,system_volume_changes,driver_ops,handoff_ms,control_ok_rate")
NOTE = "결과 파일 · /data/user/10/kr.aigyeot.app/files/scenario-results/F14-01-x.csv"


def row(k, ok=True, rate=""):
    return f"{k},{100 + k},{'true' if ok else 'false'},{'' if ok else '7.0'},,,,false,0,0,0,0,0,0,0,,{rate}"


def table(rows):
    """앱이 남기는 CSV(머리 + 회차 행 + 요약). 요약의 completed 칸은 '통과 수/회차 수'."""
    if not rows:
        return HEAD + "\n"
    passed = sum(1 for r in rows if r.split(",")[2] == "true")
    return "\n".join([HEAD, *rows, f"summary,,{passed}/{len(rows)},,,,,false,0,0,0,0,0,0,0,,"]) + "\n"


class Case:
    """상태 폴더 하나와 그 안의 가짜 기기."""

    def __init__(self, root, name):
        self.name = name
        self.st = os.path.join(root, name)
        os.makedirs(self.st)
        for f in ("installed", "debug"):
            self.touch(f)
        self.write("alive", "4321\n")
        self.write("stream", "")
        os.makedirs(self.path("files"))

    def path(self, f):
        return os.path.join(self.st, f)

    def touch(self, f):
        open(self.path(f), "w").close()

    def remove(self, f):
        if os.path.exists(self.path(f)):
            os.remove(self.path(f))

    def write(self, f, text):
        with open(self.path(f), "w", encoding="utf-8") as h:
            h.write(text)

    def app_logs(self, text):
        """앱이 SCENARIO 로그에 남기는 줄(표식 뒤)."""
        with open(self.path("stream"), "a", encoding="utf-8") as h:
            h.write(text if text.endswith("\n") else text + "\n")

    def app_file(self, name, text):
        """앱이 결과 폴더에 쓰는 파일(CSV·.done)."""
        self.write(os.path.join("files", name), text)

    def marked(self):
        return "scene-result-start-" in open(self.path("stream"), encoding="utf-8").read()

    def vehicle(self):
        if not os.path.exists(self.path("req")):
            return []
        return [l[l.index("/vehicle/"):].strip() for l in open(self.path("req")) if "/vehicle/" in l and "hvac" not in l]


def wait_for(pred, timeout=15.0):
    end = time.time() + timeout
    while time.time() < end:
        if pred():
            return True
        time.sleep(0.1)
    return False


def run(case, script, args=(), during=None, timeout=45):
    bin_dir = os.path.join(os.path.dirname(case.st), "bin")
    env = dict(os.environ, PATH=bin_dir + os.pathsep + os.environ["PATH"], SERIAL="fake-1", FAKE_ST=case.st,
               VC_PID=case.path("vc.pid"), VC_PORT="8088", ADB_TIMEOUT="3", UI_WAIT_S="2")   # 호스트의 같은 이름 변수를 물려받지 않는다
    p = subprocess.Popen([os.path.join(HERE, script), *args], env=env, start_new_session=True,
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
    note = ""
    try:
        if during:
            try:
                during(p)
            except AssertionError as e:   # 시험이 기다리던 일이 오지 않으면(표식 등) 실패로 친다
                note = f"\n[시험 진행이 막힘: {e}]"
                os.killpg(p.pid, signal.SIGKILL)
        out, _ = p.communicate(timeout=timeout)
        out += note
        if note:
            return None, out
    except subprocess.TimeoutExpired:   # 끝나지 않으면(끝없이 기다림) 실패로 친다
        os.killpg(p.pid, signal.SIGKILL)
        out = (p.communicate()[0] or "") + f"\n[{timeout}초 안에 끝나지 않음]"
    finally:
        if p.poll() is None:
            os.killpg(p.pid, signal.SIGKILL)
            p.wait()
        if os.path.exists(case.path("vc.pid")):   # 세션 신호 루프(가짜 curl)를 끈다
            try:
                os.kill(int(open(case.path("vc.pid")).read().strip()), signal.SIGTERM)
            except (ValueError, ProcessLookupError):
                pass
    return p.returncode, out


failures = []


def check(case, cond, what, out=""):
    print(("  ok   " if cond else "  FAIL ") + f"{case.name}: {what}")
    if not cond:
        failures.append(f"{case.name}: {what}\n{out}")


def after_drive(case):
    v = case.vehicle()
    return v[v.index("/vehicle/gear?gear=Drive"):] if "/vehicle/gear?gear=Drive" in v else []


def scene_cases(root):
    def then(case, text, csv=None, log=True, done=True):
        """표식이 남은 뒤 앱이 끝난다: CSV(있으면) → 로그(log) → .done 파일(done, 첫 줄 = 끝 알림 줄)."""
        def during(_p):
            assert wait_for(case.marked), "표식이 로그에 오지 않음"
            if csv is not None:
                case.app_file("F14-01-x.csv", csv)
            if log:
                case.app_logs(text)
            if done:
                time.sleep(0.3)
                case.app_file("F14-01-x.done" if text.startswith("결과 파일") else "notstarted-1.done", text.split("\n")[0] + "\n")
        return during

    one_pass = table([row(1)])

    c = Case(root, "release-build"); c.remove("debug")
    rc, out = run(c, "scene-result.sh")
    check(c, rc == 1 and "debug 빌드에만" in out and "기다린다" not in out, "release면 기다리지 않고 1", out)

    c = Case(root, "app-not-running"); c.remove("alive")
    rc, out = run(c, "scene-result.sh")
    check(c, rc == 1 and "앱이 떠 있지 않다" in out, "앱이 떠 있지 않으면 1", out)

    c = Case(root, "old-result-ignored"); c.write("before", f"{NOTE} · 요청 1회\n{one_pass}")
    c.app_file("F14-01-old.csv", one_pass); c.app_file("F14-01-old.done", f"{NOTE} · 요청 1회\n")   # 앞 장면의 결과 파일과 끝 알림 파일
    def interrupt_later(p):
        assert wait_for(c.marked)
        time.sleep(5)
        c.still = p.poll() is None
        os.killpg(p.pid, signal.SIGINT)
    rc, out = run(c, "scene-result.sh", during=interrupt_later)
    check(c, c.still and rc == 130 and "통과" not in out and "멈춤:" in out, "앞 장면 결과를 읽지 않고 기다리다 Ctrl-C면 130", out)

    c = Case(root, "mark-dropped-once"); c.write("drop", "1")
    rc, out = run(c, "scene-result.sh", during=then(c, f"{NOTE} · 요청 1회\n{one_pass}", one_pass))
    check(c, rc == 0 and "다시 남긴다" in out and "통과 1/1" in out, "표식이 한 번 빠져도 다시 남겨 0", out)

    c = Case(root, "mark-dropped-thrice"); c.write("drop", "3")
    rc, out = run(c, "scene-result.sh")
    check(c, rc == 1 and "표식 줄을 남기거나 읽지 못했다" in out, "표식이 세 번 빠지면 1", out)

    c = Case(root, "not-started")
    rc, out = run(c, "scene-result.sh", during=then(c, "시작하지 못함 · 장면 파일 오류: JSON 객체가 아니다"))
    check(c, rc == 1 and "시작하지 못함 · 장면 파일 오류" in out and not c.vehicle(), "시작하지 못하면 그 줄을 찍고 되돌리지 않고 1", out)

    old_note = "결과 파일 · /data/user/10/kr.aigyeot.app/files/scenario-results/F14-01-old.csv · 요청 1회"
    c = Case(root, "done-list-fails-once")
    c.app_file("F14-01-old.csv", one_pass); c.app_file("F14-01-old.done", old_note + "\n"); c.write("ls_fail", "1")
    rc, out = run(c, "scene-result.sh", during=then(c, f"{NOTE} · 요청 1회\n{one_pass}", one_pass, log=False))
    check(c, rc == 0 and "F14-01-x.csv" in out and "F14-01-old" not in out, "시작 때 목록 조회가 한 번 실패해도 앞 장면 .done을 새 결과로 읽지 않음", out)

    c = Case(root, "done-list-always-fails"); c.write("ls_fail", "9")
    rc, out = run(c, "scene-result.sh")
    check(c, rc == 1 and "결과 폴더 목록을 읽지 못했다" in out, "결과 폴더 목록을 끝내 못 읽으면 기다리지 않고 1", out)

    c = Case(root, "pidof-fails-once")
    def pidof_blip(_p):
        assert wait_for(c.marked)
        time.sleep(0.5); c.write("pidof_fail", "1")   # 기다리는 중 pid 확인 한 번 실패
        time.sleep(3)
        c.app_file("F14-01-x.csv", one_pass); c.app_logs(f"{NOTE} · 요청 1회\n{one_pass}")
    rc, out = run(c, "scene-result.sh", during=pidof_blip)
    check(c, rc == 0 and "앱이 꺼져" not in out and "통과 1/1" in out, "pid 확인이 한 번 실패해도 앱이 꺼진 것으로 보지 않음", out)

    c = Case(root, "csv-read-cut")   # CSV를 읽다 출력이 끊김(끝 표식 없음): 온전한 로그의 표로 판정
    c.write("partial_cat", "1")
    rc, out = run(c, "scene-result.sh", during=then(c, f"{NOTE} · 요청 1회\n{one_pass}", one_pass))
    check(c, rc == 0 and "앱 로그의 표를 쓴다" in out and "통과 1/1" in out, "CSV 출력이 끊겨도 온전한 로그 표로 0", out)

    c = Case(root, "csv-incomplete-file")   # 끝까지 읽었지만 표가 온전하지 않음(머리만): 로그의 표로 판정
    rc, out = run(c, "scene-result.sh", during=then(c, f"{NOTE} · 요청 1회\n{one_pass}", HEAD + "\n"))
    check(c, rc == 0 and "온전하지 않아 앱 로그의 표를 쓴다" in out and "통과 1/1" in out, "CSV가 온전하지 않으면 로그 표로 0", out)

    odd = "결과 파일 · /data/user/10/kr.aigyeot.app/files/scenario-results/F14 01 it's;x.csv"
    c = Case(root, "path-with-space-quote")   # 경로에 공백·작은따옴표·세미콜론: 기기 셸에서 인자 하나로 간다
    def odd_end(_p):
        assert wait_for(c.marked)
        c.app_file("F14 01 it's;x.csv", one_pass); c.app_logs(f"{odd} · 요청 1회\n{one_pass}")
    rc, out = run(c, "scene-result.sh", during=odd_end)
    check(c, rc == 0 and "읽지 못해" not in out and "통과 1/1" in out, "경로에 특수문자가 있어도 저장 파일을 읽어 0", out)

    c = Case(root, "adb-hangs-at-start")   # 시작 때 목록 조회가 응답 없이 멈춤: 시간 제한 뒤 다시 시도
    c.write("hang_ls", "1")
    rc, out = run(c, "scene-result.sh", during=then(c, f"{NOTE} · 요청 1회\n{one_pass}", one_pass))
    check(c, rc == 0 and "통과 1/1" in out, "adb가 한 번 멈춰도 시간 제한으로 끊고 이어 가 0", out)

    c = Case(root, "pidof-hangs-once")
    def pidof_hang(_p):
        assert wait_for(c.marked)
        c.write("hang_pidof", "1"); time.sleep(5)
        c.app_file("F14-01-x.csv", one_pass); c.app_logs(f"{NOTE} · 요청 1회\n{one_pass}")
    rc, out = run(c, "scene-result.sh", during=pidof_hang)
    check(c, rc == 0 and "앱이 꺼져" not in out and "통과 1/1" in out, "pid 확인이 한 번 멈춰도 앱이 꺼진 것으로 보지 않고 0", out)

    c = Case(root, "result-log-dropped")
    rc, out = run(c, "scene-result.sh", during=then(c, f"{NOTE} · 요청 1회\n{one_pass}", one_pass, log=False))
    check(c, rc == 0 and ".done으로 끝을 안다" in out and "통과 1/1" in out, "끝 알림 로그가 빠져도 .done 파일로 끝나 0", out)

    c = Case(root, "not-started-file-only")
    rc, out = run(c, "scene-result.sh", during=then(c, "시작하지 못함 · 장면 파일 오류: x", log=False))
    check(c, rc == 1 and "시작하지 못함 · 장면 파일 오류" in out and not c.vehicle(), "로그가 빠져도 .done 파일의 '시작하지 못함'으로 1", out)

    c = Case(root, "pass-from-file")
    rc, out = run(c, "scene-result.sh", during=then(c, f"{NOTE} · 요청 1회\n{one_pass}", one_pass))
    check(c, rc == 0 and "통과 1/1" in out and "읽지 못해" not in out and "/vehicle/gear?gear=Park" in c.vehicle(),
          "저장 파일로 통과하고 정차로 되돌려 0", out)

    c = Case(root, "write-failed-uses-log")
    rc, out = run(c, "scene-result.sh", during=then(c, f"{NOTE} · 요청 1회 · 결과 파일을 쓰지 못함(아래 표만 남음)\n{one_pass}"))
    check(c, rc == 0 and "앱 로그의 표를 쓴다" in out and "통과 1/1" in out, "파일을 못 썼으면 로그의 표로 0", out)

    sixty = table([row(k, rate="1.000") for k in range(1, 61)])
    full = f"{NOTE} · 요청 60회\n{sixty}"
    cut = full[:full.rfind("\n", 0, 1500) + 1]   # 로그 한 건 길이 한도로 잘린 표: 줄 경계에서 잘려 남은 회차는 모두 통과, 요약 줄과 뒤 회차가 빠짐
    assert "summary," not in cut
    c = Case(root, "truncated-log-full-file")
    rc, out = run(c, "scene-result.sh", during=then(c, cut, sixty))
    check(c, rc == 0 and "통과 60/60" in out, "로그가 잘려도 저장 파일의 60회로 0", out)

    c = Case(root, "truncated-log-no-file")
    rc, out = run(c, "scene-result.sh", during=then(c, cut))
    check(c, rc == 1 and "잘려" in out, "로그가 잘리고 파일도 못 읽으면 1", out)

    c = Case(root, "fewer-runs-than-asked")
    rc, out = run(c, "scene-result.sh", during=then(c, f"{NOTE} · 요청 2회\n{one_pass}", one_pass))
    check(c, rc == 1 and "요청한 2회 중 1회만" in out and "통과 1/2" in out, "요청보다 회차가 모자라면 1", out)

    bad = table([row(1, ok=False)])
    c = Case(root, "run-failed")
    rc, out = run(c, "scene-result.sh", during=then(c, f"{NOTE} · 요청 1회\n{bad}", bad))
    check(c, rc == 1 and "못 채움" in out and "통과 0/1" in out and "/vehicle/gear?gear=Park" in c.vehicle(),
          "회차가 실패하면 되돌린 뒤 1", out)

    c = Case(root, "no-runs")
    rc, out = run(c, "scene-result.sh", during=then(c, f"{NOTE} · 요청 1회\n{table([])}", table([])))
    check(c, rc == 1 and "끝난 회차 없음" in out, "끝난 회차가 없으면 1", out)

    c = Case(root, "app-died")
    def kill_app(_p):
        assert wait_for(c.marked)
        c.remove("alive")
    rc, out = run(c, "scene-result.sh", during=kill_app)
    check(c, rc == 1 and "앱이 꺼져" in out and "/vehicle/gear?gear=Park" in c.vehicle(), "앱이 꺼지면 정차로 되돌리고 1", out)

    c = Case(root, "app-restarted")
    def restart_app(_p):
        assert wait_for(c.marked)
        c.write("alive", "9876\n")   # 꺼진 뒤 시스템이 다른 프로세스로 다시 띄움(장면은 없음)
    rc, out = run(c, "scene-result.sh", during=restart_app)
    check(c, rc == 1 and "앱이 꺼져" in out and "/vehicle/gear?gear=Park" in c.vehicle(), "앱이 다른 pid로 다시 떠도 꺼진 것으로 보고 1", out)

    c = Case(root, "logcat-died")
    def kill_logcat(_p):
        assert wait_for(c.marked)
        time.sleep(1.5); c.touch("logcat_dead")   # 장면이 도는 중 로그 연결이 끊김
        time.sleep(3)
        c.app_file("F14-01-x.csv", one_pass); c.app_file("F14-01-x.done", f"{NOTE} · 요청 1회\n")
    rc, out = run(c, "scene-result.sh", during=kill_logcat)
    check(c, rc == 0 and "결과 폴더의 끝 알림 파일만 보며" in out and "통과 1/1" in out and "/vehicle/gear?gear=Park" in c.vehicle(),
          "logcat이 끊겨도 .done으로 끝을 알고 정차로 되돌려 0", out)

    c = Case(root, "reset-failed"); c.write("curl_fail", "gear=Park")
    rc, out = run(c, "scene-result.sh", during=then(c, f"{NOTE} · 요청 1회\n{one_pass}", one_pass))
    check(c, rc == 1 and "통과 1/1" in out and "기준 상태로 다 되돌리지 못했다" in out, "통과여도 되돌리기가 실패하면 1", out)


def drive_cases(root):
    c = Case(root, "drive-normal")
    rc, out = run(c, "drive.sh", ["1"])
    check(c, rc == 0 and after_drive(c) == [
        "/vehicle/gear?gear=Drive", "/vehicle/controls?speed=30&accelerator=0&brake=0&steering=0",
        "/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0", "/vehicle/gear?gear=Park",
        "/vehicle/body?door_rl=1", "/vehicle/body?door_rl=0"], "끝까지 달리고 정차·뒷문으로 0", out)

    for sig, name in ((signal.SIGINT, "drive-ctrl-c"), (signal.SIGTERM, "drive-term")):
        c = Case(root, name)
        def stop_mid(p, c=c, sig=sig):
            assert wait_for(lambda: "speed=30" in "".join(c.vehicle()))
            time.sleep(0.5)
            os.killpg(p.pid, sig) if sig == signal.SIGINT else p.send_signal(sig)
        rc, out = run(c, "drive.sh", ["60"], during=stop_mid)
        tail = after_drive(c)
        check(c, rc == 130 and "/vehicle/gear?gear=Park" in tail and "/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0" in tail,
              "달리는 중 멈추면 정차를 보내고 130", out)

    c = Case(root, "drive-vc-lost")
    def drop_vc(_p):
        assert wait_for(lambda: "speed=30" in "".join(c.vehicle()))
        c.touch("vc_down")   # 달리는 중 adb forward가 끊김
    rc, out = run(c, "drive.sh", ["2"], during=drop_vc)
    tail = after_drive(c)
    check(c, rc == 0 and "VC 연결을 다시 잡고" in out and tail[-2:] == ["/vehicle/body?door_rl=1", "/vehicle/body?door_rl=0"]
          and "/vehicle/gear?gear=Park" in tail, "달리는 중 VC 연결이 끊겨도 다시 잡아 정차하고 0", out)

    c = Case(root, "drive-speed30-fails"); c.write("curl_fail", "speed=30")
    rc, out = run(c, "drive.sh", ["1"])
    check(c, rc == 1 and "/vehicle/gear?gear=Park" in after_drive(c), "30km/h 요청이 실패해도 정차를 보내고 1", out)

    c = Case(root, "drive-stop-speed-fails"); c.write("curl_fail", "speed=0&accelerator=0&brake=0"); c.touch("fail_after_drive")
    rc, out = run(c, "drive.sh", ["1"])
    check(c, rc == 1 and after_drive(c).count("/vehicle/gear?gear=Park") >= 1 and "정차를 다 보내지 못했다" in out,
          "속도 0이 실패해도 기어 P를 보내고 1", out)

    c = Case(root, "drive-stop-park-fails"); c.write("curl_fail", "gear=Park"); c.touch("fail_after_drive")
    rc, out = run(c, "drive.sh", ["1"])
    check(c, rc == 1 and "/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0" in after_drive(c) and "정차를 다 보내지 못했다" in out,
          "기어 P가 실패해도 속도 0을 보내고 1", out)

    c = Case(root, "drive-door-close-fails-once"); c.write("curl_fail", "door_rl=0$"); c.write("curl_fail_n", "1")
    rc, out = run(c, "drive.sh", ["1"])
    check(c, rc == 0 and "뒷문 닫기를 다시 보낸다" in out and after_drive(c).count("/vehicle/body?door_rl=0") == 2,
          "뒷문 닫기가 한 번 실패해도 연결을 다시 잡고 닫아 0", out)

    c = Case(root, "drive-door-close-fails"); c.write("curl_fail", "door_rl=0$")
    rc, out = run(c, "drive.sh", ["1"])
    check(c, rc == 1 and after_drive(c).count("/vehicle/body?door_rl=0") >= 3 and "뒷문을 닫지 못했다" in out,
          "뒷문 닫기가 계속 실패하면 다시 시도한 뒤 알리고 1", out)

    c = Case(root, "drive-ctrl-c-door-open")
    def stop_after_open(p):
        assert wait_for(lambda: "/vehicle/body?door_rl=1" in c.vehicle())
        os.killpg(p.pid, signal.SIGINT)
    rc, out = run(c, "drive.sh", ["1"], during=stop_after_open)
    check(c, rc == 130 and c.vehicle()[-1] == "/vehicle/body?door_rl=0", "뒷문을 연 뒤 Ctrl-C면 뒷문을 닫고 130", out)

    c = Case(root, "drive-bad-arg")
    rc, out = run(c, "drive.sh", ["x"])
    check(c, rc == 1 and not c.vehicle(), "달릴 초가 잘못되면 요청 없이 1", out)

    c = Case(root, "reset-partial-fail"); c.write("curl_fail", "gear=Park")
    rc, out = run(c, "reset.sh")
    v = c.vehicle()
    check(c, rc == 1 and "/vehicle/controls?speed=0&accelerator=0&brake=30&steering=0" in v and "/vehicle/body?door_rl=0&door_rr=0" in v,
          "기준 상태 요청 하나가 실패해도 나머지를 보내고 1", out)

    c = Case(root, "stop")
    rc, out = run(c, "stop.sh")
    check(c, rc in (0, 1) and "지웠다" in out, "정리 스크립트가 공용 함수를 읽고 세션 신호·포트 연결 정리까지 간다", out)


def main():
    root = tempfile.mkdtemp(prefix="aigyeot-demo-selftest-")
    bin_dir = os.path.join(root, "bin")
    os.makedirs(bin_dir)
    for name, body in (("adb", FAKE_ADB), ("curl", FAKE_CURL)):
        with open(os.path.join(bin_dir, name), "w") as h:
            h.write(body)
        os.chmod(os.path.join(bin_dir, name), 0o755)
    print("scene-result.sh")
    scene_cases(root)
    print("drive.sh·reset.sh·stop.sh")
    drive_cases(root)
    if failures:
        print(f"\n시연 스크립트 자체 시험 실패 {len(failures)}건:\n")
        print("\n---\n".join(failures))
        sys.exit(1)
    print("시연 스크립트 자체 시험 통과")


if __name__ == "__main__":
    main()
