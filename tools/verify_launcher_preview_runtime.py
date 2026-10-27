#!/usr/bin/env python3
"""Verify and capture the complete APK's live headset layout preview on an emulator."""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = Path.home()/'Library/Android/sdk/platform-tools/adb'
ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'dev.markpronkin.unbound'


def main():
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--serial',required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'artifacts/launcher-preview'); args=parser.parse_args()
    if not args.serial.startswith('emulator-'): raise ValueError('Use an isolated emulator')
    output=args.output;output.mkdir(parents=True,exist_ok=True)
    base=[str(ADB),'-s',args.serial]
    def adb(*command,binary=False):return subprocess.check_output(base+list(command),text=not binary)
    def shell(*command):return adb('shell',*command)
    def dump():
        for _ in range(4):
            response=shell('uiautomator','dump','/sdcard/launcher-preview.xml')
            if 'dumped to' in response:
                root=ET.fromstring(shell('cat','/sdcard/launcher-preview.xml'))
                if root.find('node') is not None:return root
            time.sleep(.3)
        raise AssertionError('UI automation did not return a fresh screen')
    def find(text,description=False,root=None,prefix=False):
        root=dump() if root is None else root; key='content-desc' if description else 'text'
        return next((n for n in root.iter('node') if (n.get(key,'').startswith(text) if prefix else n.get(key,'').casefold()==text.casefold())),None)
    def click_node(node):
        assert node is not None,'Missing UI control'
        a,b,c,d=map(int,re.findall(r'\d+',node.get('bounds')));shell('input','tap',str((a+c)//2),str((b+d)//2))
    def click(text,description=False,prefix=False):click_node(find(text,description,prefix=prefix))
    def capture(name):
        root=dump();(output/(name+'.xml')).write_text(ET.tostring(root,encoding='unicode'))
        (output/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',binary=True));return root
    def page(root=None):
        root=dump() if root is None else root
        for n in root.iter('node'):
            m=re.fullmatch(r'Page (\d+) of (\d+)\s+·\s+Apps (\d+)–(\d+)',n.get('text',''))
            if m:return tuple(map(int,m.groups()))
        raise AssertionError('Missing page indicator')
    def search(value):
        click('Search apps or package names',True)
        shell('input','keycombination','113','29');shell('input','keyevent','KEYCODE_DEL')
        if value:shell('input','text',value)
        shell('input','keyevent','KEYCODE_BACK');dump()
    shell('am','force-stop',PACKAGE);shell('logcat','-c');shell('cmd','uimode','night','no')
    shell('am','start','-W','-n',PACKAGE+'/ai.nreal.nebula.activity.setting.SettingActivity','--ez','dev.beampro.settings.SETUP_PASSED','true')
    for _ in range(6):
        entry=find('App menu')
        if entry is not None:click_node(entry);break
        shell('input','swipe','540','1800','540','650','300')
    else:raise AssertionError('App menu settings entry not found')
    for _ in range(5):
        if find('Show headset preview',True) is not None:break
        time.sleep(.2)
    click('Show headset preview',True)
    root=capture('headset-preview-light');result={'offline_preview_opens':page(root)[0]==1}
    click('Next headset page',True);root=capture('headset-preview-page-2');result['next_page_starts_with_app_11']=page(root)[2]==11
    click('Previous headset page',True)
    grid=find('Headset app grid, five columns and two rows',True);a,b,c,d=map(int,re.findall(r'\d+',grid.get('bounds')))
    shell('input','swipe',str(c-80),str((b+d)//2),str(a+80),str((b+d)//2),'400')
    result['swipe_changes_page']=page()[0]==2
    search('TutorialWidgetContent');root=capture('headset-preview-search')
    tutorial=find('Tutorial, position ',True,root,True);assert tutorial is not None
    result['search_shows_app_in_its_original_context']=page(root)[3]-page(root)[2]>0
    original_position=int(re.search(r'position (\d+)',tutorial.get('content-desc')).group(1))
    click_node(tutorial)
    if original_position==1:
        click('Move to…');choices=[n for n in dump().iter('node') if n.get('text','').startswith('Before ')]
        assert len(choices)>=2;click_node(choices[1]);expected_position=2
    else:
        click('Move to beginning');expected_position=1
    root=capture('headset-preview-draft')
    moved=find('Tutorial, position ',True,root,True);assert moved is not None
    moved_position=int(re.search(r'position (\d+)',moved.get('content-desc')).group(1))
    result['draft_move_updates_preview']=moved_position==expected_position and moved_position!=original_position
    assert find('AR SPACE  ·  UNSAVED PREVIEW',False,root) is not None
    click('Undo last menu change',True);search('');click('Previous headset page',True)
    shell('cmd','uimode','night','yes');root=capture('headset-preview-dark')
    result['preview_survives_theme_change']=find('Show headset preview',True,root).get('selected')=='true'
    errors=shell('logcat','-d','-s','AndroidRuntime:E','PortSettingsUi:E');(output/'runtime-errors.txt').write_text(errors)
    result['runtime_fatal_errors']='FATAL EXCEPTION' in errors
    path=shell('pm','path',PACKAGE).strip().split('package:',1)[1];result['apk_sha256']=shell('sha256sum',path).split()[0]
    result['phone_glasses_validation']='not assessed by this isolated-emulator check';result['emulator_api']=int(shell('getprop','ro.build.version.sdk').strip())
    (output/'runtime-verification.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
    assert all(value for key,value in result.items() if isinstance(value,bool) and key!='runtime_fatal_errors')
    assert not result['runtime_fatal_errors']


if __name__=='__main__':main()
