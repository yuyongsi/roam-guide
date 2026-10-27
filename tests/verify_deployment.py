"""在work隔离目录验证首次部署、保留数据及损坏/冲突保护；不操作正式库。"""
import argparse,os,json,sqlite3,subprocess,time,uuid,urllib.request,urllib.error,http.cookiejar,hashlib,zipfile,socket
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--work',type=Path,required=True);args=parser.parse_args()
JAR=args.jar.resolve();ROOT=args.work.resolve();assert 'work' in ROOT.parts and not ROOT.exists(),'使用新的work测试目录'
ROOT.mkdir(parents=True);JAVA=Path(os.environ.get('JAVA_HOME','D:/New Folder'))/'bin/java.exe';checks=[];processes=[]
def check(ok,label):
 assert ok,label
 checks.append(label)
def free_port():
 with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
def request(port,path,method='GET',body=None,opener=None,headers=None):
 req=urllib.request.Request('http://127.0.0.1:'+str(port)+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json',**(headers or {})},method=method)
 try:r=(opener or urllib.request.build_opener()).open(req,timeout=10)
 except urllib.error.HTTPError as e:r=e
 data=r.read();return r.code,json.loads(data),dict(r.headers)
def start(name,jar=JAR,data=None,expected=True):
 directory=data or ROOT/name;directory.mkdir(parents=True,exist_ok=True);port=free_port();env=dict(os.environ,ROAM_DATA_DIR=str(directory));env.pop('ROAM_LOG_DIR',None)
 log=ROOT/(name+'.stdout');f=log.open('wb');p=subprocess.Popen([str(JAVA),'--enable-native-access=ALL-UNNAMED','-jar',str(jar),'--server.port='+str(port)],cwd=ROOT,env=env,stdout=f,stderr=subprocess.STDOUT);f.close();processes.append(p)
 deadline=time.monotonic()+25
 while p.poll() is None and time.monotonic()<deadline:
  try:
   status,body,_=request(port,'/api/health')
   if status==200 and body.get('status')=='ready':
    check(expected,name+' ready');return p,port,directory,body
  except (OSError,ValueError):pass
  time.sleep(.12)
 if p.poll() is None:p.terminate();p.wait(10);raise AssertionError(name+' timed out '+log.read_text(encoding='utf-8',errors='replace')[-1000:])
 check(not expected and p.returncode!=0,name+' safely rejected');return p,port,directory,log.read_text(encoding='utf-8',errors='replace')
def stop(p):
 if p.poll() is None:p.terminate();p.wait(10)
def login(port,name,password):
 opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));_,session,_=request(port,'/api/session',opener=opener)
 status,body,_=request(port,'/api/auth/login','POST',{'username':name,'password':password},opener,{'X-CSRF-Token':session['csrf']});check(status==200,'login '+name);return opener,{'X-CSRF-Token':body['csrf']}
def content(db):
 with sqlite3.connect(db) as c:
  return {t:sorted(c.execute('select * from '+t).fetchall(),key=repr) for (t,) in c.execute("select name from sqlite_master where type='table' and name not like 'sqlite_%' and name not like 'search_fts%' and name!='search_dirty'")}
def altered_jar(name,kind):
 path=ROOT/(name+'.jar')
 with zipfile.ZipFile(JAR) as src,zipfile.ZipFile(path,'w') as dst:
  for info in src.infolist():
   if info.filename=='BOOT-INF/classes/bootstrap/guide.db':
    if kind=='missing':continue
    data=bytearray(src.read(info));data[90]^=1;dst.writestr(info,bytes(data))
   else:dst.writestr(info,src.read(info))
 return path
try:
 p,port,data,health=start('全新 数据目录');check(health['database']=='initialized','fresh initialization identified')
 with sqlite3.connect(data/'guide.db') as c:
  expected={'cities':5,'spots':30,'guides':22,'foods':10,'users':16,'comments':66,'social_posts':12,'social_comments':24,'shared_routes':10,'trips':13,'admin_audit_logs':0}
  for table,count in expected.items():check(c.execute('select count(*) from '+table).fetchone()[0]==count,'complete demo '+table)
  check(c.execute('select name from cities where id=3').fetchone()[0]=='南阳','Chinese database content')
  check(c.execute('pragma integrity_check').fetchone()[0]=='ok' and not c.execute('pragma foreign_key_check').fetchall(),'initial integrity and foreign keys')
 check(not list(data.glob('.guide-bootstrap-*.tmp')),'staging files cleaned')
 check((data/'logs/runtime.log').is_file() and (data/'logs/requests.log').is_file(),'fresh logs available')
 admin,headers=login(port,'admin','TravelGuide!2026');login(port,'traveler','VisitChina!2026')
 _,spots,_=request(port,'/api/search?type=spot');check(spots['total']==30,'fresh public spots searchable')
 _,guides,_=request(port,'/api/search?type=guide');check(guides['total']==22,'fresh public guides searchable')
 status,rows,_=request(port,'/api/admin/spots',opener=admin);v=next(x for x in rows if x['id']==1);v['title']='部署测试保留的景点';v['published']=False
 check(request(port,'/api/admin/spots/1','PUT',v,admin,headers)[0]==200,'custom content and unpublished status saved')
 guest=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));_,session,_=request(port,'/api/session',opener=guest)
 username='deploy_'+uuid.uuid4().hex[:10];status,session,_=request(port,'/api/auth/register','POST',{'username':username,'password':'DeployTest!2026'},guest,{'X-CSRF-Token':session['csrf']});check(status==200,'new real account saved')
 uh={'X-CSRF-Token':session['csrf']};check(request(port,'/api/guides/1/comments','POST',{'body':'部署后新增的评论需要保留。'},guest,uh)[0]==200,'new comment saved')
 check(request(port,'/api/user/password','POST',{'old_password':'DeployTest!2026','new_password':'DeployChanged!2026'},guest,uh)[0]==200,'custom password saved')
 # Fail a second process on the same data directory, without disturbing the first.
 _,_,_,error=start('竞争启动',data=data,expected=False);check('另一个网站进程' in error,'data lock gives actionable reason')
 check(request(port,'/api/health')[0]==200,'first process remains available')
 before=content(data/'guide.db');stop(p)
 p2,port2,_,health2=start('原库 再次启动',data=data);check(health2['database']=='existing','existing data identified');check(content(data/'guide.db')==before,'all existing rows preserved after abrupt-stop restart')
 login(port2,username,'DeployChanged!2026');check(request(port2,'/api/spots/1')[0]==404,'unpublished content not reset')
 stop(p2)
 # Bad or unrelated existing files must stay byte-for-byte unchanged.
 for name,raw in [('空数据库',b''),('非SQLite',b'private existing data'*40),('损坏SQLite',b'SQLite format 3\0'+b'broken'*100)]:
  d=ROOT/name;d.mkdir();(d/'guide.db').write_bytes(raw);_,_,_,msg=start(name,data=d,expected=False);check((d/'guide.db').read_bytes()==raw,name+' not replaced');check('启动检查未通过' in msg,name+' Chinese startup explanation')
 d=ROOT/'其他网站库';d.mkdir()
 with sqlite3.connect(d/'guide.db') as c:c.execute('create table unrelated(value text)');c.execute("insert into unrelated values('keep')")
 old=(d/'guide.db').read_bytes();_,_,_,msg=start('其他网站库检查',data=d,expected=False);check((d/'guide.db').read_bytes()==old,'unrelated SQLite not changed')
 for kind in ['corrupt','missing']:
  bad=altered_jar('seed-'+kind,kind);_,_,d,msg=start('资源'+kind,jar=bad,expected=False);check(not (d/'guide.db').exists(),'bad '+kind+' seed does not create partial database');check(not list(d.glob('.guide-bootstrap-*.tmp')),'bad '+kind+' staging cleaned')
 # A broken unused demo resource must not replace or reset an existing database.
 p3,port3,_,_=start('旧库与错误种子',jar=ROOT/'seed-corrupt.jar',data=data);check(content(data/'guide.db')==before,'existing data never restored from embedded demo');stop(p3)
 print('PASS',len(checks),'deployment checks')
 (ROOT/'results.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf-8')
finally:
 for p in processes:stop(p)
