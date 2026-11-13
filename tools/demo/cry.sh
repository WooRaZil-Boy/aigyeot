#!/usr/bin/env bash
# 장면용 울음 녹음(16kHz 모노 16비트 PCM)을 debug 앱의 files/scenario/cry/ 로 흘려 넣는다.
# 사용법: cry.sh <녹음파일>... (파일 이름 그대로 들어간다. 장면 B=cont_75s.pcm, D·D2=cont_long.pcm, A=stop_40s.pcm, C=stop_50s.pcm)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; FILES=()
cd "$HERE" && . ./vc.sh
[ $# -ge 1 ] || die "사용법: cry.sh <녹음파일>..."
for f in "$@"; do FILES+=("$(cd "$OLDPWD" && abspath "$f")"); done
need_pleos
need_debug_app "녹음 넣기(run-as)"
D=/data/user/10/$PKG/files/scenario/cry
a shell run-as $PKG --user 10 mkdir -p "$D" || die "폴더 만들기 실패($D)"
for f in "${FILES[@]}"; do
  [ -f "$f" ] || die "파일이 없다: $f"
  n=$(basename "$f")
  [[ "$n" =~ ^[A-Za-z0-9._-]+$ ]] || die "파일 이름에 쓸 수 없는 글자가 있다: $n (영문·숫자·._- 만)"
  ADB_TIMEOUT=300 a shell "run-as $PKG --user 10 sh -c 'cat > $D/$n'" < "$f" || die "$n 넣기 실패"   # 큰 녹음이라 시간 제한을 늘린다
  want=$(wc -c < "$f" | tr -d ' ')
  got=$(dev_read "run-as $PKG --user 10 sh -c 'wc -c < $D/$n'" | tr -d ' ')   # 못 읽으면 빈 값이라 아래에서 크기가 다르다로 멈춘다
  [ "$want" = "$got" ] || die "$n 크기가 다르다(원본 $want, 기기 $got)"
  echo "넣음: $n (${got}바이트)"
done
