import os, pathlib, subprocess, tempfile, time, json
ROOT=pathlib.Path(__file__).resolve().parents[1]
SCRIPT=ROOT/'scripts'/'backup-data.ps1'
JAVA=pathlib.Path(os.environ.get('JAVA_HOME',r'D:\New Folder'))/'bin'
def run(*args,env=None):
 return subprocess.run(['powershell.exe','-NoProfile','-File',str(SCRIPT),*map(str,args)],capture_output=True,env=env)
# The path helper must preserve both current and other drive roots without creating them.
full_function=next(line for line in SCRIPT.read_text(encoding='utf-8-sig').splitlines() if line.startswith('function Full('))
probe=subprocess.run(['powershell.exe','-NoProfile','-Command',full_function+"; Full 'C:\\'; Full 'D:\\'"],capture_output=True,text=True,check=True)
assert probe.stdout.splitlines()==['C:\\','D:\\'],probe.stdout
with tempfile.TemporaryDirectory(prefix='roam-backup-test-') as t:
 p=pathlib.Path(t); data=p/'数据 space'; data.mkdir(); (data/'guide.db').write_bytes(b'SQLite format 3\x00'+b'test database'); (data/'guide.db-wal').write_bytes(b'wal'); (data/'uploads').mkdir(); (data/'uploads'/'私人 照片.jpg').write_bytes(b'image'); dest=p/'备份 space'
 env=os.environ.copy(); env['ROAM_DATA_DIR']=str(data)
 r=run('-Destination',dest,env=env); assert r.returncode==0,(r.stdout,r.stderr)
 good=next(dest.iterdir()); m=json.loads((good/'manifest.json').read_text(encoding='utf-8-sig')); assert m['status']=='complete' and {'guide.db','guide.db-wal','uploads/私人 照片.jpg'} <= {x['path'] for x in m['files']}
 assert run('-Verify',good).returncode==0
 # Malformed paths, duplicate entries and extra or missing files all fail verification.
 manifest=good/'manifest.json'; original=manifest.read_text(encoding='utf-8-sig')
 for mode in ('duplicate','traversal'):
  altered=json.loads(original)
  if mode=='duplicate': altered['files'].append(altered['files'][0])
  else: altered['files'][0]['path']='../outside'
  manifest.write_text(json.dumps(altered),encoding='utf-8-sig'); assert run('-Verify',good).returncode!=0
 manifest.write_text(original,encoding='utf-8-sig')
 extra=good/'data'/'extra';extra.write_bytes(b'x');assert run('-Verify',good).returncode!=0;extra.unlink()
 photo=good/'data'/'uploads'/'私人 照片.jpg';photo.unlink();assert run('-Verify',good).returncode!=0;photo.write_bytes(b'image')
 (good/'data'/'guide.db').write_bytes(b'corrupt'); assert run('-Verify',good).returncode!=0
 assert run('-DataDir',p/'missing','-Destination',dest).returncode!=0
 empty=p/'empty';empty.mkdir();assert run('-DataDir',empty,'-Destination',dest).returncode!=0; (empty/'guide.db').write_bytes(b'garbage'); assert run('-DataDir',empty,'-Destination',dest).returncode!=0
 assert run('-DataDir',data,'-Destination',data/'nested').returncode!=0
 diskroot=pathlib.Path(data.anchor); assert run('-DataDir',data,'-Destination',diskroot).returncode!=0; assert run('-DataDir',diskroot,'-Destination',dest).returncode!=0
 java=p/'LockHolder.java';java.write_text('import java.nio.file.*;import java.nio.channels.*;public class LockHolder { public static void main(String[] a)throws Exception {try(FileChannel c=FileChannel.open(Path.of(a[0]),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock l=c.lock()){System.out.println("LOCKED");System.out.flush();System.in.read();}}}',encoding='utf-8')
 subprocess.run([str(JAVA/'javac.exe'),str(java)],check=True)
 holder=subprocess.Popen([str(JAVA/'java.exe'),'-cp',str(p),'LockHolder',str(data/'.startup.lock')],stdin=subprocess.PIPE,stdout=subprocess.PIPE)
 try:
  assert holder.stdout.readline().strip()==b'LOCKED'; before=set(dest.iterdir()); assert run('-DataDir',data,'-Destination',dest).returncode!=0; assert set(dest.iterdir())==before
 finally: holder.stdin.write(b'x');holder.stdin.flush();holder.wait(timeout=10)
 # A locked source file forces a failure after creating the partial directory.
 holder=subprocess.Popen([str(JAVA/'java.exe'),'-cp',str(p),'LockHolder',str(data/'uploads'/'私人 照片.jpg')],stdin=subprocess.PIPE,stdout=subprocess.PIPE)
 try:
  assert holder.stdout.readline().strip()==b'LOCKED'; before=set(dest.iterdir()); assert run('-DataDir',data,'-Destination',dest).returncode!=0
  created=set(dest.iterdir())-before; assert len(created)==1 and next(iter(created)).name.endswith('.未完成'); assert good.exists()
 finally: holder.stdin.write(b'x');holder.stdin.flush();holder.wait(timeout=10)
 # Junctions in source are rejected, and destination inside source is rejected.
 junction=data/'linked'; target=p/'elsewhere';target.mkdir()
 result=subprocess.run(['cmd','/c','mklink','/J',str(junction),str(target)],capture_output=True)
 if result.returncode==0:
  assert run('-DataDir',data,'-Destination',dest).returncode!=0
  os.rmdir(junction)
 alias=p/'alias'; result=subprocess.run(['cmd','/c','mklink','/J',str(alias),str(p)],capture_output=True)
 if result.returncode==0:
  assert run('-DataDir',alias/data.name,'-Destination',dest).returncode!=0
  assert run('-DataDir',data,'-Destination',alias/'other').returncode!=0
  os.rmdir(alias)
 # A failure before creating a destination leaves existing backups untouched.
 before={x.name for x in dest.iterdir()}; assert run('-DataDir',data,'-Destination',data/'nested').returncode!=0; assert {x.name for x in dest.iterdir()}==before
 print('PASS: backup, environment, Chinese paths, WAL/photo, verification, corruption, missing/empty, destination boundary, Java lock, preserved backups')
