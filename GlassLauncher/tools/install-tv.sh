#!/usr/bin/env bash
# גרסת Linux/macOS (פחות אינטראקטיבית מהגרסה של Windows). שימוש:  ./install-tv.sh [IP] [app-release.apk]
TV="${1:-10.100.102.20}:5555"
APK="${2:-./app-release.apk}"
PKG=com.siso.glasslauncher
adb connect "$TV"
sh() { adb -s "$TV" shell "$@"; }
[ "$(adb -s "$TV" get-state 2>&1)" = "device" ] || { echo "המכשיר לא מחובר. הפעל ניפוי באגים ברשת בטלוויזיה."; exit 1; }
STAMP=$(date +%Y%m%d-%H%M%S); BK="backup-$STAMP"; mkdir -p "$BK"
sh pm list packages -f > "$BK/all-packages.txt"
sh pm list packages -d > "$BK/already-disabled.txt"
sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$BK/original-home.txt"
: > "$BK/disabled-by-script.txt"
echo "גיבוי נשמר ב-$BK"
for p in com.google.android.leanbacklauncher.recommendations com.google.android.tvrecommendations com.google.android.backdrop com.google.android.feedback com.google.android.onetimeinitializer com.google.android.partnersetup com.google.android.play.games com.google.android.videos com.google.android.music com.google.android.youtube.tvmusic; do
  grep -q "=$p\$" "$BK/all-packages.txt" || continue
  read -r -p "להשבית $p ? [y/N] " a
  if [[ "$a" =~ ^[yY] ]]; then sh pm disable-user --user 0 "$p" && echo "$p" >> "$BK/disabled-by-script.txt"; fi
done
read -r -p "לנקות מטמון של כל האפליקציות? [Y/n] " a
[[ "$a" =~ ^[nN] ]] || sh pm trim-caches 999999999999
adb -s "$TV" install -r "$APK" || exit 1
sh cmd notification allow_listener "$PKG/$PKG.MediaNotificationListener"
CUR=$(sh settings get secure enabled_accessibility_services | tr -d '\r')
SVC="$PKG/$PKG.KeyService"
case "$CUR" in *"$SVC"*) ;; ""|null) sh settings put secure enabled_accessibility_services "$SVC";; *) sh settings put secure enabled_accessibility_services "$CUR:$SVC";; esac
sh settings put secure accessibility_enabled 1
sh pm grant "$PKG" com.android.providers.tv.permission.READ_EPG_DATA 2>/dev/null
sh appops set "$PKG" SYSTEM_ALERT_WINDOW allow
sh cmd role add-role-holder --user 0 android.app.role.HOME "$PKG"
sh input keyevent KEYCODE_HOME
echo "סיום. אם המסך לא נקבע כברירת מחדל: לחץ HOME בשלט ובחר 'מסך בית זכוכית' -> תמיד."
