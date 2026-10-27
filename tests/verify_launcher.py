"""Windows PowerShell 5启动脚本的真实进程检查；仅使用work中的副本。"""
import argparse,os,subprocess,shutil,json,socket,hashlib
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--source',type=Path,required=True);parser.add_argument('--prepared',type=Path,required=True);parser.add_argument('--running',type=Path,required=True);parser.add_argument('--running-port',type=int,required=True);parser.add_argument('--work',type=Path,required=True);a=parser.parse_args()
ROOT=a.work.resolve();assert 'work' in ROOT.parts and not ROOT.exists();ROOT.mkdir(parents=True);checks=[]
def check(ok,label):
 assert ok,label
 checks.append(label)
def port():
 with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
def run(project,flags,listen=None,data=None):
 env=dict(os.environ,ROAM_TOOLS_DIR=str(ROOT/'空工具目录'),ROAM_MAVEN_REPO=str(ROOT/'空依赖目录'))
 env.pop('ROAM_DATA_DIR',None)
 if data is not None:env['ROAM_DATA_DIR']=data
 r=subprocess.run(['powershell.exe','-NoProfile','-File',str(project.resolve()/'run.ps1'),'-NoBrowser','-Port',str(listen or port()),*flags],env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=45)
 return r.returncode,r.stdout.decode('utf-8',errors='replace')
def small(name):
 q=ROOT/name;q.mkdir();shutil.copy2(a.source/'run.ps1',q/'run.ps1');shutil.copy2(a.source/'pom.xml',q/'pom.xml')
 for n in ['sql/guide.db','sql/demo-manifest.json','src/main/resources/schema.sql']:
  (q/n).parent.mkdir(parents=True,exist_ok=True);shutil.copy2(a.source/n,q/n)
 return q
code,out=run(a.running,['-CheckOnly'],a.running_port,'.\\data\\');check(code==0 and '已在运行' in out,'running instance recognized with relative trailing data path')
code,out=run(a.source,['-CheckOnly'],a.running_port);check(code==1 and '端口' in out,'another project instance rejected without stopping it')
code,out=run(a.prepared,['-CheckOnly']);check(code==0 and '环境检查通过' in out,'Windows PowerShell environment check');check('\ufffd' not in out,'Chinese preflight output is UTF-8');check(not (a.prepared/'data/guide.db').exists(),'check-only does not initialize database')
q=ROOT/'缺文件';q.mkdir();shutil.copy2(a.source/'run.ps1',q/'run.ps1');code,out=run(q,['-CheckOnly']);check(code==1 and '缺少' in out,'incomplete extraction explained')
q=small('坏初始库');db=q/'sql/guide.db';data=bytearray(db.read_bytes());data[-1]^=1;db.write_bytes(data);code,out=run(q,['-CheckOnly']);check(code==1 and '校验失败' in out,'seed checksum verified before startup');check(not (q/'data/guide.db').exists(),'bad seed never creates runtime database')
q=small('已有空库');(q/'data').mkdir();(q/'data/guide.db').write_bytes(b'');code,out=run(q,['-CheckOnly']);check(code==1 and '为避免覆盖' in out,'empty database preflight fails safely');check((q/'data/guide.db').read_bytes()==b'','empty file preserved')
q=small('离线全新缓存');code,out=run(q,['-Offline','-PrepareOnly']);check(code==1 and '无法首次离线编译' in out,'offline cold cache explained');check(not (q/'data/guide.db').exists(),'failed build never initializes data')
q=ROOT/'重命名 中文与空格';shutil.copytree(a.prepared,q,ignore=shutil.ignore_patterns('data','.git','__pycache__'));shutil.copy2(a.source/'run.ps1',q/'run.ps1')
jar=q/'target/roam-guide-0.1.0.jar';original=jar.read_bytes();before=jar.stat().st_mtime_ns
code,out=run(q,['-Offline','-PrepareOnly']);check(code==0 and '源码和运行包校验一致' in out,'renamed source copy reuses validated local jar');check(jar.stat().st_mtime_ns==before,'warm startup skips recompilation even without Maven');check(not (q/'data/guide.db').exists(),'prepare-only does not initialize data')
tampered=bytearray(original);tampered[-1]^=1;jar.write_bytes(tampered);code,out=run(q,['-Offline','-PrepareOnly']);check(code==1 and '无法首次离线编译' in out,'tampered jar cannot bypass rebuild');jar.write_bytes(original)
f=q/'src/main/resources/application.properties';f.write_bytes(f.read_bytes()+b'\n# changed source\n');code,out=run(q,['-Offline','-PrepareOnly']);check(code==1 and '无法首次离线编译' in out,'changed source invalidates cached build')
check(not (q/'data/guide.db').exists(),'failed stale build still preserves data boundary')
print('PASS',len(checks),'Windows launcher checks');(ROOT/'results.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf-8')
