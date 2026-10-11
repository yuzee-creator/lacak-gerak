import json, os, re, shutil
pkg = json.load(open('capacitor.config.json'))['appId']
dst = os.path.join('android/app/src/main/java', *pkg.split('.'))
os.makedirs(dst, exist_ok=True)
for f in os.listdir('native'):
    if f.endswith('.java'):
        s = open(os.path.join('native', f), encoding='utf-8').read()
        s = re.sub(r'^package .*;', 'package %s;' % pkg, s, count=1, flags=re.M)
        open(os.path.join(dst, f), 'w', encoding='utf-8').write(s)
mp = 'android/app/src/main/AndroidManifest.xml'
s = open(mp, encoding='utf-8').read()
if 'TrackService' not in s:
    perms = ''.join('    <uses-permission android:name="android.permission.%s" />\n' % p
                    for p in ['FOREGROUND_SERVICE', 'FOREGROUND_SERVICE_DATA_SYNC', 'POST_NOTIFICATIONS', 'WAKE_LOCK'])
    s = s.replace('<application', perms + '    <application', 1)
    s = s.replace('</application>', '        <service android:name=".TrackService" android:exported="false" android:foregroundServiceType="dataSync" />\n    </application>', 1)
    open(mp, 'w', encoding='utf-8').write(s)
print('patch ok')
