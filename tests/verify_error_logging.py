"""只在隔离环境测试错误处理、脱敏、请求追踪和日志轮转。
需要 work/ 中的 error-qa 测试控制器；它不进入交付源码或正式运行包。
"""
import os,sys,io,json,re,sqlite3,uuid,gzip,time,concurrent.futures
from pathlib import Path
from urllib.parse import quote
sys.path.insert(0,str(Path(__file__).resolve().parents[3]/'work'))
from http_client import Session
BASE=os.environ.get('ROAM_BASE','http://127.0.0.1:8820');DB=Path(os.environ['ROAM_QA_DB']);LOG=Path(os.environ['ROAM_QA_LOG'])
assert 'work' in DB.parts and 'work' in LOG.parts and ':8818' not in BASE
checks=[];token='qa-private-'+uuid.uuid4().hex
def check(ok,label):
 assert ok,label
 checks.append(label)
def request(method,path,body=None,headers=None,session=None,files=None):return (session or Session()).request(method,BASE+path,json=body,headers=headers,files=files)
def contract(r,status,code):
 check(r.status_code==status,'status '+code)
 data=r.json();check(data['status']==status and data['code']==code and bool(data['message']),'unified shape '+code)
 check(re.fullmatch(r'[a-f0-9-]{36}',data['request_id']) is not None,'server-generated id '+code)
 check(data['request_id']==r.headers.get('X-Request-ID'),'header/body correlation '+code)
 check('no-store' in r.headers.get('Cache-Control',''),'errors not cached '+code)
 return data
for method,path,body,status,code in [
 ('GET','/api/spots/not-a-number',None,400,'INVALID_REQUEST'),
 ('GET','/api/search?page=abc',None,400,'INVALID_REQUEST'),
 ('GET','/api/spots/999999999',None,404,'NOT_FOUND'),
 ('GET','/api/admin/audit',None,401,'AUTH_REQUIRED'),
 ('GET','/api/auth/login',None,405,'METHOD_NOT_ALLOWED'),
 ('POST','/api/auth/login',{},403,'FORBIDDEN'),
 ('GET','/api/qa/errors/runtime?private='+quote(token),None,500,'SERVER_ERROR')]:
 r=request(method,path,body);v=contract(r,status,code);check(token not in r.text and 'Exception' not in r.text and 'stack' not in r.text,'no exception details '+code)
unknown=request('GET','/does-not-exist/'+token+'?password='+token,headers={'Accept':'text/html','Cookie':'private-cookie='+token,'Authorization':'Bearer '+token})
check(unknown.status_code==404 and 'text/html' in unknown.headers.get('Content-Type',''),'friendly HTML 404')
check(unknown.headers.get('Cache-Control')=='no-store' and unknown.headers.get('X-Content-Type-Options')=='nosniff','HTML errors no cache and no content sniffing')
check('返回首页' in unknown.text and '重新加载' in unknown.text and '查看问题编号' in unknown.text,'404 recovery controls')
check(token not in unknown.text and unknown.headers['X-Request-ID'] in unknown.text,'HTML excludes reflected private path')
r=request('GET','/qa-error?private='+token,headers={'Accept':'text/html'});check(r.status_code==500 and '这一步暂时没有完成' in r.text and r.headers['X-Request-ID'] in r.text,'friendly HTML 500')
check(token not in r.text and 'IllegalStateException' not in r.text,'HTML 500 redacted')
contract(request('GET','/api/spots/not-a-number',headers={'Accept':'text/html'}),400,'INVALID_REQUEST')
check(request('GET','/error?status=500').status_code==404,'direct /error cannot forge status')
r=request('GET','/api/session',headers={'X-Request-ID':token});check(r.status_code==200 and re.fullmatch(r'[a-f0-9-]{36}',r.headers.get('X-Request-ID','')) is not None and r.headers['X-Request-ID']!=token,'ignore untrusted caller request id')
s=Session();csrf=s.get(BASE+'/api/session').json()['csrf'];r=request('POST','/api/auth/login',{'username':'admin','password':'TravelGuide!2026'},headers={'X-CSRF-Token':csrf},session=s);check(r.status_code==200,'admin login');heads={'X-CSRF-Token':r.json()['csrf']}
current=next(v for v in s.get(BASE+'/api/admin/spots').json() if v['id']==1)
r=request('PUT','/api/admin/spots/1',current,heads,s);check(r.status_code==200,'audited mutation succeeds')
entry=s.get(BASE+'/api/admin/audit').json()['items'][0];check(entry['request_id']==r.headers['X-Request-ID'],'audit and access log share request id')
r=request('PUT','/api/admin/spots/1',current,heads,s);v=contract(r,409,'CONFLICT');check(s.get(BASE+'/api/admin/audit').json()['items'][0]['request_id']==v['request_id'],'failed audit shares error id')
# Bad JSON, content type and absent multipart field should all remain expected errors.
import urllib.request,urllib.error
for data,content,status,code in [(b'{broken','application/json',400,'INVALID_REQUEST'),(b'plain','text/plain',415,'UNSUPPORTED_MEDIA_TYPE')]:
 req=urllib.request.Request(BASE+'/api/auth/login',data=data,headers={**heads,'Content-Type':content},method='POST')
 try:r0=s.opener.open(req)
 except urllib.error.HTTPError as e:r0=e
 body=json.loads(r0.read());check(r0.status==status and body['code']==code and body['request_id']==r0.headers['X-Request-ID'],'binding error '+content)
contract(request('POST','/api/admin/uploads',{},heads,s),400,'INVALID_REQUEST')
contract(request('POST','/api/admin/uploads',headers=heads,session=s,files={'file':('private-'+token+'.jpg',io.BytesIO(b'x'*(5*1024*1024+1024)),'image/jpeg')}),413,'UPLOAD_TOO_LARGE')
# Fault injection: database rollback and safe diagnostics, no SQL/error-message content.
before=next(v for v in s.get(BASE+'/api/admin/spots').json() if v['id']==1)
with sqlite3.connect(DB) as c:c.execute("CREATE TRIGGER qa_private_error BEFORE UPDATE ON spots WHEN NEW.id=1 BEGIN SELECT RAISE(ABORT,'"+token+"'); END;")
try:
 r=request('PUT','/api/admin/spots/1',before,heads,s);v=contract(r,500,'SERVER_ERROR');check(token not in r.text,'SQL detail not returned')
 after=next(v for v in s.get(BASE+'/api/admin/spots').json() if v['id']==1);check(after==before,'failed database write rolls back')
finally:
 with sqlite3.connect(DB) as c:c.execute('DROP TRIGGER qa_private_error')
def text_logs():
 out=[]
 for f in LOG.rglob('*'):
  if f.suffix=='.log':out.append(f.read_text(encoding='utf-8'))
  elif f.suffix=='.gz':
   try:
    with gzip.open(f,'rt',encoding='utf-8') as z:out.append(z.read())
   except OSError:pass
 return '\n'.join(out)
text=text_logs();check(token not in text,'logs exclude headers query paths passwords upload filename and SQL exception messages')
check('type=java.lang.IllegalStateException' in text and 'QaFailureController' in text,'safe exception type and stack location recorded')
check(v['request_id'] in text,'error id searchable in files')
check('route=/api/qa/errors/runtime' in text and 'elapsed_ms=' in text and 'actor=' in text,'access diagnostics recorded')
check((LOG/'runtime.log').is_file() and (LOG/'requests.log').is_file(),'separate persistent log files')
reqid=v['request_id'];runtime='\n'.join(f.read_text(encoding='utf-8') for f in LOG.glob('runtime.log'));check(reqid in runtime or any(reqid in gzip.open(f,'rt',encoding='utf-8').read() for f in (LOG/'archive').glob('runtime*.gz')),'server failure logged with same id')
# Each file is deliberately small in this isolated launch. Exercise real rolling policies.
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:list(pool.map(lambda _:request('GET','/api/session').status_code,range(360)))
for _ in range(12):request('GET','/api/qa/errors/runtime?private='+token)
deadline=time.monotonic()+8
while time.monotonic()<deadline:
 archives=list((LOG/'archive').glob('*.gz'))
 if archives and all(sum(f.stat().st_size for f in archives if f.name.startswith(name+'.'))<=8192 for name in ['runtime','requests']):break
 time.sleep(.1)
archives=list((LOG/'archive').glob('*.gz'));check(any(f.name.startswith('requests.') for f in archives),'real request log rollover')
check(any(f.name.startswith('runtime.') for f in archives),'real runtime log rollover')
check(all(sum(f.stat().st_size for f in archives if f.name.startswith(name+'.'))<=8192+4096 for name in ['runtime','requests']),'archive cap cleanup with one rolling-file allowance')
check(all(min(int(f.name.split('.')[-3]) for f in archives if f.name.startswith(name+'.'))>0 for name in ['runtime','requests']),'oldest archives pruned as size grows')
import datetime
old=(datetime.datetime.now()-datetime.timedelta(days=3)).strftime('%Y-%m-%d');check(not any(old in f.name for f in archives),'expired history removed on startup')
check(token not in text_logs(),'rolled archives also contain no private probe values')
with sqlite3.connect(DB) as c:check(c.execute('pragma integrity_check').fetchone()[0]=='ok' and not c.execute('pragma foreign_key_check').fetchall(),'database remains consistent')
print('PASS',len(checks),'logging and error checks')
Path('work/error-logging-checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf-8')
