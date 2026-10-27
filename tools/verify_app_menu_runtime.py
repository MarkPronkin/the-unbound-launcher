#!/usr/bin/env python3
"""Exercise the production phone editor on an explicitly selected test device."""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = Path.home() / 'Library/Android/sdk/platform-tools/adb'
PKG = 'dev.markpronkin.unbound'
LAYOUT = f'/sdcard/Android/data/{PKG}/files/AppDetails/EPHInfoConfig/LayoutConfig.json'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--report-dir', type=Path, default=ROOT/'artifacts/app-menu')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        raise ValueError('This destructive fixture test is restricted to isolated emulators')
    output = args.report_dir; output.mkdir(parents=True, exist_ok=True)
    adb = [str(ADB), '-s', args.serial]
    def run(*command, binary=False):
        return subprocess.check_output(adb+list(command), text=not binary)
    def shell(*command): return run('shell', *command)
    def dump(name=None):
        shell('uiautomator', 'dump', '/sdcard/app-menu-window.xml')
        xml=shell('cat','/sdcard/app-menu-window.xml')
        if name: (output/(name+'.xml')).write_text(xml)
        return ET.fromstring(xml)
    def node(text, desc=False, root=None):
        root = dump() if root is None else root
        key='content-desc' if desc else 'text'
        return next((n for n in root.iter('node') if n.get(key,'').casefold()==text.casefold()),None)
    def tap(text,desc=False):
        found=node(text,desc)
        if found is None: raise AssertionError('Missing UI control: '+text)
        x1,y1,x2,y2=map(int,re.findall(r'\d+',found.get('bounds')))
        shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))
    def screenshot(name):
        dump(name)
        (output/(name+'.png')).write_bytes(run('exec-out','screencap','-p',binary=True))
    def editor():
        shell('am','start','-n',PKG+'/ai.nreal.nebula.activity.setting.SettingActivity','--ez','dev.beampro.settings.SETUP_PASSED','true')
        for _ in range(6):
            if node('App menu') is not None: tap('App menu');break
            shell('input','swipe','540','1800','540','650','300')
        else: raise AssertionError('App menu settings entry unavailable')
        for _ in range(5):
            root=dump()
            if node('Search apps or package names',True,root) is not None: break
            time.sleep(.3)
        else: raise AssertionError('App menu editor did not open')
    def search(value):
        tap('Search apps or package names',True)
        shell('input','keyevent','KEYCODE_MOVE_END')
        shell('input','keyevent','--longpress','KEYCODE_DEL')
        # Select-all via Ctrl+A followed by delete works for any previous query.
        shell('input','keycombination','113','29')
        shell('input','keyevent','KEYCODE_DEL')
        if value: shell('input','text',value)
        shell('input','keyevent','KEYCODE_BACK')
    def options(prefix):
        root=dump();found=next((n for n in root.iter('node') if n.get('content-desc','').startswith(prefix)),None)
        assert found is not None, prefix
        x1,y1,x2,y2=map(int,re.findall(r'\d+',found.get('bounds')))
        shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))
    def layout(): return json.loads(shell('cat',LAYOUT))
    def save():
        tap('Save app menu',True)
        dump()  # Wait for the click callback and synchronous atomic save to complete.
    result={}
    shell('logcat','-c')
    shell('cmd','uimode','night','no')
    editor(); screenshot('editor-light')
    try: initial=layout()
    except subprocess.CalledProcessError: initial=None
    root=dump(); parents={child:parent for parent in root.iter() for child in parent}
    rows=[parents[n] for n in root.iter('node') if n.get('content-desc','').startswith('Options for ')]
    assert len(rows)>=2
    coordinates=[]
    for row in rows[:2]:
        x1,y1,x2,y2=map(int,re.findall(r'\d+',row.get('bounds')));coordinates.append((x1+120,(y1+y2)//2))
    shell('input','touchscreen','draganddrop',str(coordinates[0][0]),str(coordinates[0][1]),str(coordinates[1][0]),str(coordinates[1][1]),'800')
    root=dump();result['drag_creates_editable_draft']=node('Save app menu',True,root).get('enabled')=='true'
    save()
    if initial:
        first=min((key for key in initial if initial[key]<2147483647),key=initial.get)
        result['drag_updates_saved_order']=layout()[first]==1

    search('TutorialWidgetContent'); options('Options for Tutorial, position'); tap('Move to end');save()
    saved=layout(); result['filtered_reorder_writes_shared_unity_layout']=saved['TutorialWidgetContent']==max(x for x in saved.values() if x<2147483647)
    options('Options for Tutorial, position');tap('Hide from app menu');save()
    result['hide_writes_native_sentinel']=layout()['TutorialWidgetContent']==2147483647
    tap('Show hidden apps',True);screenshot('editor-hidden')
    shell('am','force-stop',PKG);editor();tap('Show hidden apps',True)
    result['hidden_app_survives_process_restart']=node('Tutorial') is not None
    options('Options for Tutorial, hidden');tap('Show in app menu');save()
    result['restore_removes_hidden_sentinel']=layout()['TutorialWidgetContent']<2147483647
    tap('Show all apps',True);search('PhotoWidgetContent');options('Options for Photos, position');tap('Move to…')
    # There are two EditTexts while the destination dialog is open. Use its hint via XML.
    found=node('Find an app to move before')
    assert found is not None
    x1,y1,x2,y2=map(int,re.findall(r'\d+',found.get('bounds')))
    shell('input','tap',str((x1+x2)//2),str((y1+y2)//2));shell('input','text','TutorialWidgetContent')
    root=dump();choice=next((n.get('text') for n in root.iter('node') if n.get('text','').startswith('Before Tutorial')),None)
    assert choice;tap(choice);save()
    saved=layout();result['searchable_destination_moves_globally']=saved['PhotoWidgetContent']+1==saved['TutorialWidgetContent']
    shell('cmd','uimode','night','yes');time.sleep(.3);screenshot('editor-dark')
    result['dark_mode_editor_available']=node('App menu') is not None
    # Return to an unfiltered dark screenshot and exercise discard without saving.
    search('');screenshot('editor-dark-all')
    search('PhotoWidgetContent');options('Options for Photos, position');tap('Move to beginning');tap('Undo last menu change',True)
    before=layout();shell('input','keyevent','KEYCODE_BACK');tap('Discard')
    result['discard_leaves_saved_layout_unchanged']=layout()==before
    errors=shell('logcat','-d','-s','AndroidRuntime:E','PortSettingsUi:E')
    (output/'runtime-errors.txt').write_text(errors)
    result['runtime_fatal_errors']='FATAL EXCEPTION' in errors
    apk_path=shell('pm','path',PKG).strip().split('package:',1)[1]
    result['apk_sha256']=shell('sha256sum',apk_path).split()[0]
    result['device']=args.serial;result['phone_hardware_validation']='pending; no phone connected'
    (output/'runtime-verification.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))
    assert all(v for k,v in result.items() if isinstance(v,bool) and k!='runtime_fatal_errors')
    assert not result['runtime_fatal_errors']


if __name__=='__main__':main()
