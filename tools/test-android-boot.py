#!/usr/bin/env python3
"""Reboot and reload rollback tests on a disposable emulator; never a physical phone."""
import argparse,json,subprocess,time,urllib.request
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial',required=True);args=p.parse_args()
assert args.serial.startswith('emulator-'),'Disposable emulator only'
adb_path=str(Path(__file__).resolve().parents[1]/'.local-tools/android-sdk/platform-tools/adb')
package='io.github.liuw.surgebridge.debug';port=None;token='';checks=0
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def adb(*a):return subprocess.run([adb_path,'-s',args.serial,*a],check=True,capture_output=True,timeout=60).stdout

def api(path,body=None):
 req=urllib.request.Request(f'http://127.0.0.1:{port}/v1/{path}',data=None if body is None else json.dumps(body).encode(),headers={'Host':'127.0.0.1:19090','Authorization':'Bearer '+token,'Content-Type':'application/json'})
 with opener.open(req,timeout=90) as r:return json.load(r)

def connect():
 global port,token
 if port:
  try:adb('forward','--remove',f'tcp:{port}')
  except subprocess.CalledProcessError:pass
 port=int(adb('forward','tcp:0','tcp:19090'))
 adb('shell','am','start','-n',package+'/io.nekohasekai.sfa.compose.MainActivity')
 for _ in range(60):
  try:
   token=adb('shell','run-as',package,'cat','files/debug-api-token').decode().strip();api('health');return
  except Exception:time.sleep(.25)
 raise AssertionError('API unavailable')

def wait(state):
 for _ in range(80):
  if api('status')['service']==state:return
  time.sleep(.25)
 raise AssertionError('Service state '+state+' not reached')

def check(ok,label):
 global checks
 assert ok,label
 checks+=1;print('PASS',label,flush=True)

def reboot():
 adb('reboot');adb('wait-for-device')
 for _ in range(120):
  if adb('shell','getprop','sys.boot_completed').decode().strip()=='1':break
  time.sleep(.5)
 else:raise AssertionError('Boot timeout')
 adb('shell','input','keyevent','82');connect()

connect();previous=api('status')['selectedProfileId'];boot=api('boot')['restoreOnBoot']
try:
 api('service/stop',{});wait('Stopped')
 source={'log':{'level':'info'},'inbounds':[{'type':'tun','tag':'test-tun','address':['172.19.0.1/30'],'auto_route':True,'stack':'mixed'}],'outbounds':[{'type':'direct','tag':'DIRECT'}],'route':{'final':'DIRECT'}}
 item=api('profiles/import',{'name':'Boot and rollback fixture','source':json.dumps(source)});id=item['id']
 api('profiles/select',{'id':id});api('service/start',{});wait('Started')
 digest=next(x for x in api('profiles')['profiles'] if x['id']==id)['conversion']['runtimeSha256']
 bad=json.loads(json.dumps(source));bad['inbounds'].append({'type':'http','listen':'127.0.0.1','listen_port':19090,'tag':'occupied-by-debug-api'})
 api('profiles/update',{'id':id,'source':json.dumps(bad)});api('service/reload',{});wait('Started')
 restored=next(x for x in api('profiles')['profiles'] if x['id']==id)
 check(restored['conversion']['runtimeSha256']==digest,'runtime bind failure restores last running configuration')
 check(restored['refresh'].get('success') is False,'rollback outcome observable')
 api('boot',{'restoreOnBoot':True});reboot();wait('Started')
 check(api('status')['selectedProfileId']==id,'enabled boot recovery restores selected VPN')
 api('boot',{'restoreOnBoot':False});reboot();time.sleep(4)
 check(api('status')['service']=='Stopped','disabled boot recovery does not start VPN')
 api('boot',{'restoreOnBoot':True});api('service/start',{});wait('Started');api('service/stop',{});wait('Stopped');reboot();time.sleep(4)
 check(api('status')['service']=='Stopped','manual stop respected across reboot')
 print(f'PASS: {checks} reboot/rollback assertions',flush=True)
finally:
 try:
  api('service/stop',{});wait('Stopped');api('boot',{'restoreOnBoot':boot})
  if previous>0:api('profiles/select',{'id':previous})
 except Exception:pass
 if port:
  try:adb('forward','--remove',f'tcp:{port}')
  except subprocess.CalledProcessError:pass
