#!/usr/bin/env python3
"""Check the real firmware warning on an isolated emulator and restore its display settings."""
import argparse
import json
from pathlib import Path
import re
import subprocess

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default='emulator-5580')
    parser.add_argument('--apk',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/firmware-warning-design/runtime')
    args=parser.parse_args()
    if not args.serial.startswith('emulator-'):parser.error('Use an isolated emulator')
    out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
    adb=[str(Path.home()/'Library/Android/sdk/platform-tools/adb'),'-s',args.serial]
    def run(*command):
        return subprocess.check_output([*adb,*map(str,command)],text=True,timeout=120)
    def previous(kind):
        value=run('shell','wm',kind)
        match=re.search(r'Override '+kind+r':\s*(\S+)',value)
        return match.group(1) if match else 'reset'
    size,density=previous('size'),previous('density')
    font=run('shell','settings','get','system','font_scale').strip()
    results=[]
    print(run('install','-r',args.apk),flush=True)
    try:
        for name,dimensions,scale in [('small-portrait','640x960','1.0'),('short-landscape','960x640','1.0'),('large-text','640x960','1.5')]:
            run('shell','wm','size',dimensions);run('shell','wm','density','320')
            run('shell','settings','put','system','font_scale',scale)
            result=run('shell','am','instrument','-w','dev.beampro.settings.preview/dev.beampro.port.FirmwareWarningChecks')
            (out/(name+'.txt')).write_text(result)
            print(name+': '+result.strip(),flush=True)
            if 'Firmware warning runtime checks passed: 12' not in result:raise RuntimeError(result)
            results.append({'case':name,'checks':12})
    finally:
        run('shell','wm','size',size);run('shell','wm','density',density)
        if font=='null':run('shell','settings','delete','system','font_scale')
        else:run('shell','settings','put','system','font_scale',font)
    run('shell','am','start','-W','-S','-n','dev.beampro.settings.preview/dev.beampro.port.ConnectionPreviewActivity','--ez','firmware','true','--ez','hold','true')
    run('shell','uiautomator','dump','/sdcard/unbound-firmware-warning.xml')
    (out/'firmware-warning.xml').write_text(run('shell','cat','/sdcard/unbound-firmware-warning.xml'))
    screenshot=subprocess.check_output([*adb,'exec-out','screencap','-p'],timeout=30)
    (out/'firmware-warning-native.png').write_bytes(screenshot)
    (out/'checks.json').write_text(json.dumps(results,indent=2)+'\n')


if __name__=='__main__':main()
