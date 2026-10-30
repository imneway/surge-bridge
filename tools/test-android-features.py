#!/usr/bin/env python3
"""Disposable-emulator checks for independent updates, modes and failure-only switching.
Uses synthetic profiles, an ephemeral TLS root scoped to one fixture profile, and local
Snell v6 servers. Never run against a physical phone. Requires openssl and the native core.
"""
import argparse, datetime, hashlib, http.server, json, os, socket, sqlite3, ssl, struct
import subprocess, tempfile, threading, time, urllib.request, urllib.error
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--core', default=str(ROOT/'.local-tools/sing-box-1.14.4-darwin-arm64/sing-box'))
a = p.parse_args()
assert a.serial.startswith('emulator-'), 'Disposable emulator only'
ADB = str(ROOT/'.local-tools/android-sdk/platform-tools/adb'); PACKAGE = 'io.github.liuw.surgebridge.debug'
checks = 0; port = None; token = None
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def adb(*args, **kw):
    return subprocess.run([ADB, '-s', a.serial, *args], check=True, capture_output=True, timeout=40, **kw).stdout

def connect():
    global port, token
    if port:
        try: adb('forward', '--remove', f'tcp:{port}')
        except subprocess.CalledProcessError: pass
    port = int(adb('forward', 'tcp:0', 'tcp:19090'))
    for _ in range(40):
        try:
            token = adb('shell','run-as',PACKAGE,'cat','files/debug-api-token').decode().strip()
            api('health');return
        except Exception:time.sleep(.25)
    raise RuntimeError('API startup timeout')

def api(path, body=None):
    req = urllib.request.Request(f'http://127.0.0.1:{port}/v1/{path}',data=None if body is None else json.dumps(body).encode(),headers={'Host':'127.0.0.1:19090','Authorization':'Bearer '+str(token),'Content-Type':'application/json'})
    with opener.open(req,timeout=120) as r:return json.load(r)

def expect(ok, label):
    global checks
    assert ok,label
    checks += 1; print('PASS',label,flush=True)

def wait_until(fn, seconds=20):
    until=time.monotonic()+seconds
    while time.monotonic()<until:
        if fn():return
        time.sleep(.5)
    raise AssertionError('Timed out waiting for expected state')

def profiles():return api('profiles')['profiles']
def profile(i):return next(x for x in profiles() if x['id']==i)
def stop():
    api('service/stop',{});wait_until(lambda:api('status')['service']=='Stopped')
def launch():
    adb('shell','am','start','-n',PACKAGE+'/io.nekohasekai.sfa.compose.MainActivity');connect()
def find_runtime(digest):
    for path in adb('shell','run-as',PACKAGE,'find','files/configs','-name','*.source').decode().splitlines():
        snapshot=json.loads(adb('shell','run-as',PACKAGE,'cat',path))
        if digest in snapshot:return path[:-7],snapshot[digest]
    raise AssertionError('Snapshot not found')
def put_private(path, content):
    assert path.startswith(('files/configs/','databases/')) and all(c.isalnum() or c in '/._-' for c in path)
    adb('shell',f"run-as {PACKAGE} sh -c 'cat > {path}'",input=content)

def age_remote_profiles(temp):
    # Parcelable's final fields are lastUpdated (int64 LE) and interval (int32 LE).
    # Stop the disposable app before copying SQLite so its WAL cannot race the fixture.
    adb('shell','am','force-stop',PACKAGE)
    names=adb('shell','run-as',PACKAGE,'ls','databases').decode().splitlines()
    dbname=next(n for n in names if n.endswith('.db') and 'profile' in n.lower())
    for name in names:
        if name.startswith(dbname): (temp/name).write_bytes(adb('shell','run-as',PACKAGE,'cat','databases/'+name))
    with sqlite3.connect(temp/dbname) as db:
        table=next(row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'") if row[0].lower()=='profiles')
        rows=db.execute(f'SELECT id,typed FROM {table}').fetchall()
        for id,blob in rows:
            if id not in remote_ids:continue
            old=struct.unpack('<q',blob[-12:-4])[0]
            assert abs(old-time.time()*1000)<120_000,'Unexpected parcel layout'
            db.execute(f'UPDATE {table} SET typed=? WHERE id=?',(blob[:-12]+struct.pack('<q',0)+blob[-4:],id))
        db.commit();db.execute('PRAGMA wal_checkpoint(TRUNCATE)')
    put_private('databases/'+dbname,(temp/dbname).read_bytes())
    for suffix in ['-wal','-shm']:
        adb('shell','run-as',PACKAGE,'rm','-f','databases/'+dbname+suffix)
    launch()

launch();stop()
previous=api('status')['selectedProfileId']
for old in profiles():
    if old['type']=='Remote':api('profiles/schedule',{'id':old['id'],'autoUpdate':False,'intervalMinutes':60})
source='[General]\nipv6=false\ndns-server=1.1.1.1\n[Proxy]\nA=snell,192.0.2.1,443,psk=synthetic-test-only,version=6\n[Rule]\nFINAL,DIRECT'
counts={'/a':0,'/b':0};fixture_status=200
class Subscription(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        counts[self.path]=counts.get(self.path,0)+1
        body=source.encode();self.send_response(fixture_status);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def log_message(self,*_):pass
subscriptions=http.server.ThreadingHTTPServer(('127.0.0.1',0),Subscription)
threading.Thread(target=subscriptions.serve_forever,daemon=True).start()
reverse=int(adb('reverse','tcp:0',f'tcp:{subscriptions.server_port}'))
remote_ids=[];servers=[];processes=[];logs=[]
try:
    with tempfile.TemporaryDirectory(prefix='surge-android-features-') as d:
        temp=Path(d)
        for path,enabled in [('/a',True),('/b',False)]:
            item=api('profiles/import',{'name':'Update fixture '+path,'url':f'http://127.0.0.1:{reverse}{path}','autoUpdate':enabled,'intervalMinutes':15 if enabled else 360})
            remote_ids.append(item['id'])
        expect(profile(remote_ids[0])['autoUpdate'] and not profile(remote_ids[1])['autoUpdate'],'automatic update independent per profile')
        age_remote_profiles(temp)
        api('profiles/check-due',{})
        expect(counts['/a']>=2 and counts['/b']==1,'due checks fetch enabled profile only')
        api('profiles/update',{'id':remote_ids[1]})
        expect(counts['/b']==2,'manual refresh works with automatic update disabled')
        expect(profile(remote_ids[1])['refresh']['success'],'refresh result observable')
        api('profiles/schedule',{'id':remote_ids[0],'autoUpdate':False,'intervalMinutes':120})
        expect(profile(remote_ids[0])['intervalMinutes']==120 and not profile(remote_ids[0])['autoUpdate'],'changed interval and disable persisted')
        before=profile(remote_ids[1])['conversion']['runtimeSha256'];fixture_status=503
        try:api('profiles/update',{'id':remote_ids[1]});raise AssertionError('503 accepted')
        except urllib.error.HTTPError as e:expect(e.code==422,'download failure rejected')
        expect(profile(remote_ids[1])['conversion']['runtimeSha256']==before and not profile(remote_ids[1])['refresh']['success'],'failed update keeps runtime and records failure')
        fixture_status=200
        # Self-signed fixture root: TLS verification remains enabled; only this generated
        # profile trusts it. This does not install a device-wide CA or modify user profiles.
        (temp/'cert.cnf').write_text('[req]\nprompt=no\ndistinguished_name=dn\nx509_extensions=ext\n[dn]\nCN=www.gstatic.com\n[ext]\nsubjectAltName=DNS:www.gstatic.com\nbasicConstraints=critical,CA:TRUE\nkeyUsage=critical,digitalSignature,keyEncipherment,keyCertSign\nextendedKeyUsage=serverAuth\n')
        subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(temp/'key.pem'),'-out',str(temp/'cert.pem'),'-days','1','-config',str(temp/'cert.cnf')],check=True,capture_output=True)
        class Target(http.server.BaseHTTPRequestHandler):
            def do_HEAD(self):
                time.sleep(.03);self.send_response(204);self.end_headers()
            def do_GET(self):
                self.send_response(200);self.end_headers();self.wfile.write(b'CONTROLLED-SNELL-OK')
            def log_message(self,*_):pass
        tls=http.server.ThreadingHTTPServer(('127.0.0.1',0),Target)
        ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);ctx.load_cert_chain(temp/'cert.pem',temp/'key.pem')
        tls.socket=ctx.wrap_socket(tls.socket,server_side=True)
        plain=http.server.ThreadingHTTPServer(('127.0.0.1',0),Target)
        for srv in [tls,plain]:threading.Thread(target=srv.serve_forever,daemon=True).start();servers.append(srv)
        ports=[]
        for i in range(3):
            with socket.socket() as sock:sock.bind(('127.0.0.1',0));ports.append(sock.getsockname()[1])
            cfg={'log':{'level':'error'},'inbounds':[{'type':'snell','listen':'0.0.0.0','listen_port':ports[-1],'version':6,'psk':'fixture-only'}],'outbounds':[{'type':'direct','tag':'direct'}], 'route':{'rules':[{'port':443,'action':'route','outbound':'direct','override_address':'127.0.0.1','override_port':tls.server_port},{'action':'route','outbound':'direct','override_address':'127.0.0.1','override_port':plain.server_port}]}}
            # Restrict listener to loopback: emulator 10.0.2.2 maps host loopback.
            cfg['inbounds'][0]['listen']='127.0.0.1'
            (temp/f'node{i}.json').write_text(json.dumps(cfg));log=open(temp/f'node{i}.log','w');logs.append(log)
            processes.append(subprocess.Popen([a.core,'run','-c',str(temp/f'node{i}.json')],stdout=log,stderr=log))
        conf='[General]\nipv6=false\n[Proxy]\n'+'\n'.join(f'{name}=snell,10.0.2.2,{port},version=6,psk=fixture-only' for name,port in zip(['A','B','C'],ports))+'\n[Proxy Group]\nProxy=select,A,B,C,DIRECT\n[Rule]\nIP-CIDR,0.0.0.0/0,DIRECT,no-resolve\nFINAL,Proxy'
        item=api('profiles/import',{'name':'Failure-only fixture','source':conf});id=item['id']
        options={'policy':'Proxy','packages':['com.android.chrome'],'failoverGroups':['Proxy']}
        result=api('profiles/overrides',{'id':id,'overrides':options})
        file,snapshot=find_runtime(result['conversion']['runtimeSha256'])
        runtime=json.loads(adb('shell','run-as',PACKAGE,'cat',file));runtime['certificate']={'certificate':[ (temp/'cert.pem').read_text() ]}
        data=json.dumps(runtime).encode();digest=hashlib.sha256(data).hexdigest()
        put_private(file+'.source',json.dumps({digest:snapshot}).encode());put_private(file,data)
        api('profiles/select',{'id':id})
        assert api('status')['vpnPermissionGranted'],'Approve the emulator VPN dialog before running this test'
        api('service/start',{});wait_until(lambda:api('status')['service']=='Started')
        wait_until(lambda:set(api('mode')['available'])=={'Rule','Global','Direct'})
        expect(True,'three native routing modes available')
        api('mode',{'mode':'Global'});wait_until(lambda:api('mode')['current']=='Global')
        def http_probe():
            return adb('shell','toybox','nc','-w','2','203.0.113.17','18080',input=b'GET / HTTP/1.0\r\nHost: fixture\r\n\r\n')
        expect(b'CONTROLLED-SNELL-OK' in http_probe(),'Global overrides catch-all DIRECT and reaches Snell')
        api('mode',{'mode':'Direct'});wait_until(lambda:api('mode')['current']=='Direct')
        expect(b'CONTROLLED-SNELL-OK' not in http_probe(),'Direct bypasses proxy')
        wait_until(lambda:api('failover').get('Proxy',{}).get('reason')=='paused_direct',50)
        expect(True,'failure probing paused in Direct mode')
        api('mode',{'mode':'Rule'});wait_until(lambda:api('mode')['current']=='Rule')
        expect(b'CONTROLLED-SNELL-OK' not in http_probe(),'Rule preserves original catch-all DIRECT')
        def selected():return next(g['selected'] for g in api('groups')['groups'] if g['tag']=='Proxy')
        wait_until(lambda:api('failover').get('Proxy',{}).get('reason')=='healthy',80)
        expect(selected()=='A','healthy current node retained')
        processes[0].terminate();processes[0].wait(timeout=5)
        wait_until(lambda:api('failover').get('Proxy',{}).get('reason')=='probe_failed',90)
        expect(selected()=='A','first failure does not switch')
        wait_until(lambda:selected()=='B',90)
        expect(True,'second failed round switches to first healthy backup B')
        processes[0]=subprocess.Popen([a.core,'run','-c',str(temp/'node0.json')],stdout=logs[0],stderr=logs[0])
        time.sleep(65)
        expect(selected()=='B','recovered A does not cause automatic failback')
        api('mode',{'mode':'Global'});stop();api('service/start',{});wait_until(lambda:api('status')['service']=='Started')
        wait_until(lambda:api('mode')['current']=='Global')
        expect(selected()=='B','mode and selected backup survive service restart')
        print(f'PASS: {checks} Android feature assertions',flush=True)
finally:
    try:
        stop()
        if previous>0:api('profiles/select',{'id':previous})
        for i in remote_ids:api('profiles/schedule',{'id':i,'autoUpdate':False,'intervalMinutes':360})
    except Exception:pass
    for proc in processes:
        if proc.poll() is None:proc.terminate();proc.wait(timeout=5)
    for log in logs:log.close()
    for srv in servers:srv.shutdown();srv.server_close()
    subscriptions.shutdown();subscriptions.server_close()
    adb('reverse','--remove',f'tcp:{reverse}')
    if port:adb('forward','--remove',f'tcp:{port}')
