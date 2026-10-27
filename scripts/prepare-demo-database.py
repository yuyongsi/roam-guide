"""维护用工具：从已审阅的演示快照生成首启数据库及校验清单；老师运行网站不需要Python。
拒绝未知账号/行程，防止把后续真实用户数据意外打入初始数据包。
"""
import argparse,base64,hashlib,json,os,sqlite3,tempfile
from pathlib import Path

NAMES={'admin','traveler','小林同学','慢半拍','周末出发','镜头朝外','带爸妈散步','清汤少辣','看展不赶路','一碗热面','听雨走街','地图先行','木窗与青瓦','留半天发呆','同行小记','轻装慢走'}
TRIPS={3,10,30,59,*range(100,109)}
COUNTS={'cities':5,'spots':30,'guides':22,'foods':10,'users':16,'comments':66,'social_posts':12,'social_comments':24,'shared_routes':10,'trips':13,'admin_audit_logs':0}
def password_hash(password):
 salt=os.urandom(16);digest=hashlib.pbkdf2_hmac('sha256',password.encode(),salt,210000,32)
 return '210000:'+base64.b64encode(salt).decode()+':'+base64.b64encode(digest).decode()
def prepare(source,output):
 if source.resolve()==output.resolve():raise ValueError('输入和输出必须不同；先生成新文件，核对后再替换演示快照。')
 output.parent.mkdir(parents=True,exist_ok=True)
 if output.exists():raise ValueError('输出文件已存在，不自动覆盖。')
 with sqlite3.connect('file:'+source.resolve().as_posix()+'?mode=ro',uri=True) as src:
  assert {r[0] for r in src.execute('select username from users')}==NAMES,'存在未批准的用户，停止导出'
  assert {r[0] for r in src.execute('select id from trips')}==TRIPS,'存在未批准的行程，停止导出'
  assert src.execute('select count(*) from travel_memories').fetchone()[0]==0,'不能导出旅行足迹'
  assert src.execute('select count(*) from memory_photos').fetchone()[0]==0,'不能导出私人照片'
  assert src.execute('select count(*) from feedback').fetchone()[0]==0,'不能导出私人反馈'
  with sqlite3.connect(output) as dest:src.backup(dest)
 with sqlite3.connect(output) as db:
  db.execute('PRAGMA foreign_keys=ON')
  for table in ['admin_audit_logs','notifications','spot_favorites','guide_favorites','food_favorites','route_favorites','comment_likes','social_likes']:
   if db.execute("select 1 from sqlite_master where type='table' and name=?",(table,)).fetchone():db.execute('delete from '+table)
  for username, in db.execute('select username from users').fetchall():
   password='TravelGuide!2026' if username=='admin' else 'VisitChina!2026' if username=='traveler' else 'RoamDemo!2026'
   db.execute('update users set password_hash=?,auth_version=1,must_change_password=0,status=\'ACTIVE\' where username=?',(password_hash(password),username))
  db.execute("delete from sqlite_sequence where name in ('admin_audit_logs','notifications')")
  for table,expected in COUNTS.items():assert db.execute('select count(*) from '+table).fetchone()[0]==expected,table
  for table in ['cities','spots','guides','foods']:
   assert not db.execute("select 1 from "+table+" where image!='' and image not like '/assets/%'").fetchone(),'存在运行时上传图'
  assert not db.execute('pragma foreign_key_check').fetchall()
  assert db.execute('pragma integrity_check').fetchone()[0]=='ok'
  db.commit();db.execute('PRAGMA wal_checkpoint(TRUNCATE)');db.execute('PRAGMA journal_mode=DELETE');db.execute('VACUUM');db.commit()
 manifest={'format':1,'application':'roam-guide','dataset_version':'classroom-demo-20261026','sha256':hashlib.sha256(output.read_bytes()).hexdigest(),'counts':COUNTS,'description':'课程演示账号、内容与示例行程；不包含真实到访、私人照片、反馈或历史管理员操作。'}
 output.with_name('demo-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 print('演示数据库与清单已生成；未修改运行库。')
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--source',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args();prepare(args.source,args.output)
