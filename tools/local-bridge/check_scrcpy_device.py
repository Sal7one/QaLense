#!/usr/bin/env python3
"""Real mirror/SDK smoke check. Requires an already approved disposable sample emulator.

Uses the running desktop HTTP API, not mocks. It never enables a host SDK, approves a
phone request, kills adb or resets app data. Screen evidence goes to temporary storage.
"""
import argparse
import json
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import threading
import time
import re
import xml.etree.ElementTree as ET
import zipfile
from urllib.request import Request, urlopen
from urllib.parse import urlsplit


def run(url, adb, fixture=False, hd=False):
    if urlsplit(url).hostname not in {'127.0.0.1', 'localhost'}: raise ValueError('Use the loopback desktop URL')
    session = json.load(urlopen(url + '/api/bootstrap'))['session']
    connection = None
    def api(name, body=None):
        headers = {'X-Qalens-Session': session, 'Content-Type': 'application/json'}
        if connection: headers['X-Qalens-Connection'] = connection
        with urlopen(Request(url + '/api/' + name, data=json.dumps(body).encode() if body is not None else None, headers=headers), timeout=15) as response:
            return json.load(response)
    def wait(message, condition, seconds=10):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if condition(): return
            time.sleep(.1)
        raise AssertionError(message)
    work = api('workbench'); connection = work['connectionId']
    if fixture:
        current=work.get('connection') or next((p for p in work.get('profiles',[]) if p.get('serial','').startswith('emulator-') and p.get('package')=='com.qalens.sample'),{})
        if not current.get('serial','').startswith('emulator-') or current.get('package')!='com.qalens.sample': raise ValueError('First connect the disposable sample emulator')
        api('connect',{'profile':{**current,'devicePort':18766},'token':'synthetic-scrcpy-desktop-0123456789'})
        work=api('workbench'); connection=work['connectionId']
        wait('Fixture bridge did not become connected',lambda:api('connection/check',{'reconnect':True})['phase']=='connected',20)
        work=api('workbench')
    if hd and not fixture: raise ValueError('HD automation requires the scrcpyGuiSeconds test fixture')
    if hd and not shutil.which('ffmpeg'): raise ValueError('Install ffmpeg before testing HD archive decoding')
    if not work['connected'] or work['phase'] != 'connected' or work['connection']['package'] != 'com.qalens.sample' or not work['connection']['serial'].startswith('emulator-'):
        raise ValueError('Connect and approve com.qalens.sample on a disposable emulator first')
    serial = work['connection']['serial']
    def shell(*args):
        return subprocess.check_output([adb, '-s', serial, 'shell', *args], timeout=10).decode().strip()
    owned = None; response = None; recording = False
    stopped = threading.Event(); errors = []; stats = {'frames':0,'sizes':[]}
    modes = {'revision':0,'modeEpoch':1}
    def mirror_input(action, **fields):
        return api('mirror/input', {'streamId':owned['streamId'], 'connectionId':connection, **modes, 'action':action, **fields})
    def mode(name):
        result = api('preview', {'enabled':True, 'mode':name, 'streamId':owned['streamId'], 'connectionId':connection})
        modes.update(revision=result['revision'],modeEpoch=result['modeEpoch'])
    def snapshot(): return api('snapshot')
    def node(tag):
        found = next((n for n in snapshot()['nodes'] if n.get('tag') == tag),None)
        if not found: raise AssertionError('Expected sample tag ' + tag)
        return found
    def tap(tag):
        snap = snapshot(); target=next(n for n in snap['nodes'] if n.get('tag')==tag)
        viewport=snap.get('screenViewport',snap['viewport']); bounds=target['bounds']; origin=snap['viewport']
        x=((bounds['left']+bounds['right'])/2+origin.get('originX',0))/viewport['width']
        y=((bounds['top']+bounds['bottom'])/2+origin.get('originY',0))/viewport['height']
        mirror_input('touch',state='down',x=x,y=y); mirror_input('touch',state='up',x=x,y=y)
        time.sleep(.5)
    original_rotation = shell('settings','get','system','user_rotation')
    original_accel = shell('settings','get','system','accelerometer_rotation')
    with tempfile.TemporaryDirectory(prefix='qalens-scrcpy-device-') as temporary:
        video_path = Path(temporary)/'stream.h264'
        try:
            if not fixture: api('launch',{})
            def ready():
                try:
                    current=snapshot()
                    return bool(current['nodes']) and (not fixture or any(n.get('tag')=='scrcpy.title' for n in current['nodes']))
                except Exception: return False
            wait('Sample Compose UI did not become ready',ready)
            api('inspection',{'enabled':False})
            owned=api('preview',{'enabled':True,'mode':'control','connectionId':connection})
            modes['modeEpoch']=owned['modeEpoch']
            headers={'X-Qalens-Session':session,'X-Qalens-Connection':connection,'X-Qalens-Mirror':owned['streamId']}
            response=urlopen(Request(url+'/api/mirror/video',headers=headers),timeout=10)
            def exact(count):
                data=response.read(count)
                if len(data)!=count: raise AssertionError('Interrupted HTTP video stream')
                return data
            def receive():
                try:
                    assert exact(4)==b'h264'
                    written=0
                    with video_path.open('wb') as output:
                        while not stopped.is_set():
                            header=exact(12)
                            if header[0]&128:
                                _,width,height=struct.unpack('>III',header)
                                stats['sizes'].append((width,height)); modes['revision']+=1
                            else:
                                size=struct.unpack('>I',header[8:])[0]
                                assert 0<size<=2*1024*1024
                                payload=exact(size)
                                if written+size<8*1024*1024: output.write(payload); output.flush(); written+=size
                                if not header[0]&64: stats['frames']+=1
                except Exception as error:
                    if not stopped.is_set(): errors.append(str(error))
            receiver=threading.Thread(target=receive,daemon=True); receiver.start()
            def keep_alive():
                while not stopped.wait(1):
                    try: api('mirror/heartbeat',{'streamId':owned['streamId'],'connectionId':connection})
                    except Exception as error:
                        if not stopped.is_set(): errors.append(str(error))
            keeper=threading.Thread(target=keep_alive,daemon=True); keeper.start()
            wait('No live H.264 frames',lambda:stats['frames']>3 and bool(stats['sizes']))
            assert not errors,errors
            print('PASS: authenticated HTTP video, initial frames and scaled dimensions',stats['sizes'][0])
            if fixture:
                tap('scrcpy.tap'); wait('Native tap did not change the fixture',lambda:'Taps: 1' in json.dumps(snapshot()))
            else:
                for _ in range(4):
                    if any(n.get('tag')=='nav.tab.accounts' for n in snapshot()['nodes']): break
                    mirror_input('key',keycode=4); time.sleep(.4)
                tap('nav.tab.accounts'); wait('Native touch did not navigate',lambda:any(n.get('tag')=='accounts.title' for n in snapshot()['nodes']))
            print('PASS: continuous scrcpy touch navigates the actual sample')
            before=snapshot()['screen']; mode('inspect'); api('inspection',{'enabled':True})
            target=node('scrcpy.title' if fixture else 'accounts.title'); api('command',{'action':'select','id':target['id']})
            assert api('selection')['selectedId']==target['id']
            exported=api('component',{'id':target['id']})
            assert exported['document']['content']['component']['tag']==('scrcpy.title' if fixture else 'accounts.title')
            assert snapshot()['screen']==before,'Inspection activated the host app'
            try: mirror_input('touch',state='down',x=.5,y=.5)
            except Exception: pass
            else: raise AssertionError('Inspect accepted a host tap')
            mode('preview')
            try: mirror_input('key',keycode=4)
            except Exception: pass
            else: raise AssertionError('Preview accepted a host key')
            api('inspection',{'enabled':False}); mode('control')
            print('PASS: SDK selection/attributes remain linked, Inspect/Preview reject host input')
            if fixture:
                tap('scrcpy.input'); mirror_input('key',keycode=123)
                for _ in range(7): mirror_input('key',keycode=67)
                mirror_input('text',text='QA123.45')
                wait('Native keyboard text was not visible in semantics',lambda:'QA123.45' in json.dumps(api('component',{'id':node('scrcpy.input')['id']})))
                mirror_input('key',keycode=4)
                print('PASS: real editable phone text input and Back through scrcpy control')
            else:
                tap('nav.tab.home')
            anchor=node('scrcpy.row.0' if fixture else 'home.greeting')
            count=stats['frames']
            for _ in range(4): mirror_input('scroll',x=.5,y=.6,horizontal=0,vertical=-2); time.sleep(.1)
            wait('Mouse wheel did not move the actual scroll content', lambda:not any(n.get('tag')==anchor['tag'] and n['bounds']['top']==anchor['bounds']['top'] for n in snapshot()['nodes']))
            wait('No frames after scroll',lambda:stats['frames']>count)
            old_revision=modes['revision']; shell('settings','put','system','accelerometer_rotation','0'); shell('settings','put','system','user_rotation','1')
            wait('Rotation did not publish a new video session',lambda:modes['revision']>old_revision)
            assert stats['sizes'][-1][0]>stats['sizes'][-1][1]
            shell('settings','put','system','user_rotation','0'); wait('Portrait did not restore',lambda:stats['sizes'][-1][0]<stats['sizes'][-1][1])
            print('PASS: wheel scroll, actual rotation/session restart and stale revision invalidation')
            api('recording',{'action':'start','video':False}); recording=True
            wait('SDK recording did not start',lambda:api('recordings/device')['controls']['phase']=='capturing')
            mark=api('recording',{'action':'clip','seconds':10,'label':'scrcpy smoke bug'})
            assert mark['controls']['markedClips']>0
            count=stats['frames']
            image_request=Request(url+'/api/screenshot',data=json.dumps({'includeOverlay':False}).encode(),headers={'X-Qalens-Session':session,'X-Qalens-Connection':connection,'Content-Type':'application/json'})
            with urlopen(image_request,timeout=10) as image: assert image.read(8)==b'\x89PNG\r\n\x1a\n'
            wait('Mirror stopped during SDK capture',lambda:stats['frames']>count)
            api('recording',{'action':'stop'}); recording=False
            wait('SDK evidence did not finish saving',lambda:api('recordings/device')['controls']['phase']=='idle',20)
            assert not errors,errors
            print('PASS: SDK frame recording, last-10s bug mark and masked screenshot coexist with live video')
            if hd:
                baseline={item['name'] for item in api('recordings/device')['items']}
                api('recording',{'action':'start','video':True}); recording=True
                # Actual API 36 system consent; choose only nodes from the observed Android UI.
                deadline=time.monotonic()+35
                approved=False
                while time.monotonic()<deadline:
                    shell('uiautomator','dump','/sdcard/qalens-scrcpy-consent.xml')
                    root=ET.fromstring(shell('cat','/sdcard/qalens-scrcpy-consent.xml'))
                    nodes=list(root.iter('node'))
                    def click(target):
                        coordinates=[int(x) for x in re.findall(r'\d+',target.get('bounds',''))]
                        if len(coordinates)==4: shell('input','tap',str((coordinates[0]+coordinates[2])//2),str((coordinates[1]+coordinates[3])//2))
                    chooser=next((n for n in nodes if n.get('class')=='android.widget.Spinner' and n.get('package')=='com.android.systemui'),None)
                    entire=next((n for n in nodes if n.get('text','').lower()=='share entire screen'),None)
                    button=next((n for n in nodes if n.get('text','').lower() in {'start','start now','start recording','share screen'} and n.get('package')=='com.android.systemui' and n.get('enabled')=='true'),None)
                    if button is not None and entire is not None: click(button); approved=True; break
                    if button is None and entire is not None: click(entire); continue
                    if chooser is not None: click(chooser); continue
                    time.sleep(.2)
                assert approved,'Could not approve the observed emulator projection dialog'
                shell('rm','-f','/sdcard/qalens-scrcpy-consent.xml')
                wait('HD did not start beside scrcpy',lambda:api('recordings/device')['controls']['phase']=='capturing',20)
                time.sleep(4)
                api('recording',{'action':'clip','seconds':10,'label':'scrcpy HD smoke'})
                time.sleep(2); api('recording',{'action':'stop'}); recording=False
                wait('HD did not save',lambda:api('recordings/device')['controls']['phase']=='idle',30)
                new=[item for item in api('recordings/device')['items'] if item['name'] not in baseline]
                assert any(item['name'].startswith('session_') for item in new) and any(item['name'].startswith('clip_') for item in new),'HD master/clip missing'
                for item in new:
                    copied=api('recordings/receive',{'name':item['name'],'connectionId':connection})
                    with urlopen(Request(url+'/api/recordings/file?id='+copied['id'],headers={'X-Qalens-Session':session}),timeout=15) as data:
                        archive=Path(temporary)/(copied['id']+'.sal'); archive.write_bytes(data.read())
                    with zipfile.ZipFile(archive) as saved:
                        videos=[name for name in saved.namelist() if name.endswith('.mp4')]
                        assert videos,'HD archive has no MP4'
                        for index,name in enumerate(videos):
                            media=Path(temporary)/f"{copied['id']}-{index}.mp4"; media.write_bytes(saved.read(name))
                            # Preserve the variable-rate source clock while validating every frame.
                            subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-xerror','-i',str(media),'-an','-fps_mode','passthrough','-enc_time_base','demux','-f','null','-'],check=True,timeout=15)
                assert not errors,errors
                print('PASS: actual OS-approved SDK HD + last-10s clip + master/clip MP4 decoding beside scrcpy')
        finally:
            stopped.set()
            if recording:
                try: api('recording',{'action':'stop'})
                except Exception: pass
            for key,value in (('user_rotation',original_rotation),('accelerometer_rotation',original_accel)):
                shell('settings','delete' if value=='null' else 'put','system',key,*([] if value=='null' else [value]))
            if owned:
                try: api('preview',{'enabled':False,'streamId':owned['streamId'],'connectionId':connection})
                except Exception: pass
            if response: response.close()
        if shutil.which('ffmpeg'):
            subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-xerror','-i',str(video_path),'-an','-f','null','-'],check=True,timeout=15)
            print('PASS: actual encoded HTTP video decodes in FFmpeg')
        previous=owned['streamId']
        next_mirror=api('preview',{'enabled':True,'mode':'control','connectionId':connection})
        assert next_mirror['streamId']!=previous
        try: api('mirror/input',{'streamId':previous,'connectionId':connection,**modes,'action':'key','keycode':4})
        except Exception: pass
        else: raise AssertionError('Restart accepted an old input lease')
        api('preview',{'enabled':False,'streamId':next_mirror['streamId'],'connectionId':connection})
        print('PASS: stop/restart creates a fresh stream and rejects old input')
        print('OK: real emulator scrcpy + desktop HTTP + QaLens SDK smoke cases pass; browser rendering is a separate check')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url',required=True)
    parser.add_argument('--adb',default=shutil.which('adb'))
    parser.add_argument('--fixture',action='store_true',help='Connect the running scrcpyGuiSeconds test fixture (synthetic token only)')
    parser.add_argument('--hd',action='store_true',help='Test HD with real Android consent on that disposable fixture; requires ffmpeg')
    args=parser.parse_args()
    if not args.adb: parser.error('Pass --adb with the Android platform-tools adb path')
    run(args.url.rstrip('/'),args.adb,args.fixture,args.hd)
