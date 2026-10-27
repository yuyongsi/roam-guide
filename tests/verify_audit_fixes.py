"""严格检查实际接口：不自动补充缺失版本，只清理本次创建的QA内容。"""
from pathlib import Path
import os,json,time,copy,sqlite3
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlencode
from http_client import requests
ROOT=Path(__file__).resolve().parents[1];BASE=os.getenv('ROAM_BASE','http://127.0.0.1:8818');DB=Path(os.getenv('ROAM_QA_DB',str(ROOT/'data/guide.db')))
checks=[];users=[];contents=[]
def check(name,value=True):
 if not value:raise AssertionError(name)
 checks.append(name)
class Client:
 def __init__(self):self.s=requests.Session();self.csrf=self.s.get(BASE+'/api/session').json()['csrf']
 def raw(self,method,path,body=None):return self.s.request(method,BASE+path,json=body,headers={'X-CSRF-Token':self.csrf})
 def call(self,method,path,body=None,status=200):
  r=self.raw(method,path,body);assert r.status_code==status,(method,path,r.status_code,r.text[:350]);d=r.json()
  if isinstance(d,dict)and'csrf'in d:self.csrf=d['csrf']
  return d
 def content(self,typ,id):return next(i for i in self.call('GET','/api/admin/'+typ)if i['id']==id)
def trip_body(title):return {'title':title,'days':2,'start_date':'','notes':'','items':[{'spot_id':4,'day_number':1,'note':''},{'spot_id':23,'day_number':2,'note':''}],'checklist':[]}
def scalar(sql,*args):
 with sqlite3.connect(DB)as db:return db.execute(sql,args).fetchone()[0]
try:
 admin,a,b,guest=Client(),Client(),Client(),Client();stamp=str(time.time_ns())[-10:]
 admin.call('POST','/api/auth/login',{'username':'admin','password':'TravelGuide!2026'})
 for c,name in [(a,'qa_fix_a_'+stamp),(b,'qa_fix_b_'+stamp)]:
  user=c.call('POST','/api/auth/register',{'username':name,'password':'QaTravel!2026'})['user'];users.append((user['id'],name))
 for typ in ['spots','guides']:
  data={'title':'QA版本保护'+stamp+typ,'city_id':3,'intro':'测试简介','image':'/assets/nanyang-hero.jpg','published':True,'theme':'人文历史','duration_minutes':120,'source_url':''}
  if typ=='spots':data.update(description='独立正文',gallery=['/assets/nanyang-hero.jpg'],highlights='保留看点',transportation='保留交通',tips='保留提示')
  else:data.update(subtitle='保留副标题',sections=[{'title':'小节标题','body':'完整正文'}],spot_ids=[4,23])
  id=admin.call('POST','/api/admin/'+typ,data)['id'];contents.append((typ,id));path='/api/admin/'+typ+'/'+str(id);old=admin.content(typ,id)
  check(typ+'现有和新建内容都有正版本',old['revision']==1)
  if typ=='spots':check('后台景点相册输出为数组',isinstance(old['gallery'],list))
  admin.call('PUT',path+'/publication',{'published':False,'revision':old['revision'],'title':'不得覆盖标题'})
  hidden=admin.content(typ,id);check(typ+'快捷下架只修改状态和版本',hidden['published']==0 and hidden['revision']==2 and all(hidden[k]==old[k]for k in ['title','intro','image']+(['gallery','description']if typ=='spots'else['sections','spot_ids'])))
  result=guest.call('GET','/api/search?'+urlencode({'q':old['title'],'type':'spot'if typ=='spots'else'guide'}));check(typ+'快捷下架退出全文检索',result['total']==0)
  admin.call('PUT',path+'/publication',{'published':True,'revision':1},status=409);check(typ+'旧发布版本不能覆盖当前状态',admin.content(typ,id)['published']==0)
  admin.call('PUT',path+'/publication',{'published':True},status=400)
  admin.call('PUT',path+'/publication',{'published':'true','revision':2},status=400)
  guest.call('PUT',path+'/publication',{'published':True,'revision':2},status=401)
  a.call('PUT',path+'/publication',{'published':True,'revision':2},status=403)
  check(typ+'发布严格校验版本类型及权限')
  admin.call('PUT',path+'/publication',{'published':True,'revision':2});row=admin.content(typ,id)
  check(typ+'快捷重新发布进入全文检索',guest.call('GET','/api/search?'+urlencode({'q':old['title'],'type':'spot'if typ=='spots'else'guide'}))['total']==1)
  newer={**row,'title':'QA最新内容'+stamp+typ};admin.call('PUT',path,newer)
  stale={**row,'intro':'来自旧窗口的更改'};admin.call('PUT',path,stale,status=409)
  check(typ+'旧窗口完整编辑被拒绝且新标题保留',admin.content(typ,id)['title']==newer['title'])
  fresh=admin.content(typ,id);bad=copy.deepcopy(fresh)
  if typ=='spots':bad['gallery']=['https://invalid.test/file.jpg']
  else:bad['spot_ids']=[1]
  admin.call('PUT',path,bad,status=400)
  check(typ+'验证失败事务回滚版本及所有内容',admin.content(typ,id)==fresh)
  missing=copy.deepcopy(fresh);missing.pop('revision');admin.call('PUT',path,missing,status=400);check(typ+'完整编辑也不能绕过版本')
  payloads=[{**fresh,'intro':'Concurrent '+str(n)}for n in range(2)]
  with ThreadPoolExecutor(max_workers=2)as pool:codes=list(pool.map(lambda v:admin.raw('PUT',path,v).status_code,payloads))
  check(typ+'并发同版本仅一次成功且不会锁升级报500',sorted(codes)==[200,409])
 tid=a.call('POST','/api/me/trips',trip_body('QA删除保护'+stamp))['id'];tp='/api/me/trips/'+str(tid);t=a.call('GET',tp)
 with sqlite3.connect(DB)as db:db.execute('INSERT INTO trip_budgets(trip_id,people,notes) VALUES(?,1,?)',(tid,'QA budget'))
 versions=scalar('SELECT count(*) FROM trip_versions WHERE trip_id=?',tid)
 a.call('DELETE',tp,{},status=400);b.call('DELETE',tp,{'revision':t['revision']},status=404);admin.call('DELETE',tp,{'revision':t['revision']},status=404)
 check('删除需要版本且保持本人权限',a.call('GET',tp)['id']==tid)
 updated={**t,'title':'QA另一个窗口的新内容'};new=a.call('PUT',tp,updated)
 a.call('DELETE',tp,{'revision':t['revision']},status=409)
 check('旧窗口删除不损失新行程历史和预算',a.call('GET',tp)['title']==new['title'] and scalar('SELECT count(*) FROM trip_versions WHERE trip_id=?',tid)>versions and scalar('SELECT count(*) FROM trip_budgets WHERE trip_id=?',tid)==1)
 a.call('DELETE',tp,{'revision':new['revision']});a.call('GET',tp,status=404)
 check('当前版本确认删除原子级联清理',scalar('SELECT count(*) FROM trip_versions WHERE trip_id=?',tid)==0 and scalar('SELECT count(*) FROM trip_budgets WHERE trip_id=?',tid)==0)
 tid=a.call('POST','/api/me/trips',trip_body('QA发布保护'+stamp))['id'];tp='/api/me/trips/'+str(tid);t=a.call('GET',tp)
 pub={'title':'QA公开预览'+stamp,'description':'必须保留的已填写介绍','confirm_public':True,'revision':0,'trip_revision':t['revision']}
 bad=copy.deepcopy(pub);bad.pop('trip_revision');a.call('POST',tp+'/publish',bad,status=400)
 b.call('POST',tp+'/publish',pub,status=404);check('公开预览要求源版本并保持本人权限')
 changed=copy.deepcopy(t);changed['items']=changed['items'][:1];new=a.call('PUT',tp,changed)
 a.call('POST',tp+'/publish',pub,status=409)
 check('过期首次预览不会创建任何公开路线',scalar('SELECT count(*) FROM shared_routes WHERE source_trip_id=?',tid)==0)
 pub['trip_revision']=new['revision'];rid=a.call('POST',tp+'/publish',pub)['id'];rp='/api/routes/'+str(rid);public=guest.call('GET',rp)
 check('重新确认后的公开安排等于当前预览',[i['spot_id']for i in public['items']]==[4]and public['description']==pub['description'])
 pub['revision']=public['revision'];changed=copy.deepcopy(new);changed['days']=3;latest=a.call('PUT',tp,changed)
 a.call('POST',tp+'/publish',pub,status=409)
 check('过期更新预览不修改已有公开版本',guest.call('GET',rp)==public)
 pub['trip_revision']=latest['revision'];a.call('POST',tp+'/publish',pub)
 check('重新预览后可正常更新已有公开路线',guest.call('GET',rp)['days']==3)
finally:
 with sqlite3.connect(DB)as db:
  db.execute('PRAGMA foreign_keys=ON')
  for uid,name in users:
   if db.execute('SELECT username FROM users WHERE id=?',(uid,)).fetchone()==(name,):db.execute('DELETE FROM trips WHERE user_id=?',(uid,));db.execute('DELETE FROM users WHERE id=?',(uid,))
  for typ,id in reversed(contents):db.execute('DELETE FROM '+typ+' WHERE id=?',(id,))
  db.commit()
 guest.call('GET','/api/search?q=QA')
 with sqlite3.connect(DB)as db:
  assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
  assert not db.execute('PRAGMA foreign_key_check').fetchall()
(ROOT/'target').mkdir(exist_ok=True);(ROOT/'target/audit-fixes-checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf-8')
print('PASS',len(checks),'strict HTTP/SQLite audit-fix checks')
