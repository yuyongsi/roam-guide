"""省市目录、新增去重、待完善隔离与展示条件的严格集成检查。"""
from pathlib import Path
import os,json,copy,sqlite3,time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlencode
from http_client import requests
ROOT=Path(__file__).resolve().parents[1];BASE=os.getenv('ROAM_BASE','http://127.0.0.1:8818');DB=Path(os.getenv('ROAM_QA_DB',str(ROOT/'data/guide.db')))
checks=[];cities=[];contents=[];uid=None
def check(name,ok=True):
 if not ok:raise AssertionError(name)
 checks.append(name)
class C:
 def __init__(self):self.s=requests.Session();self.csrf=self.s.get(BASE+'/api/session').json()['csrf']
 def raw(self,method,path,body=None):return self.s.request(method,BASE+path,json=body,headers={'X-CSRF-Token':self.csrf})
 def call(self,method,path,body=None,status=200):
  r=self.raw(method,path,body);assert r.status_code==status,(method,path,r.status_code,r.text[:350]);d=r.json()
  if isinstance(d,dict)and'csrf'in d:self.csrf=d['csrf']
  return d
 def city(self,id):return next(c for c in self.call('GET','/api/admin/cities')if c['id']==id)
def count(sql,*args):
 with sqlite3.connect(DB)as db:return db.execute(sql,args).fetchone()[0]
def search(c,q,type='spot'):return c.call('GET','/api/search?'+urlencode({'q':q,'type':type,'size':24}))
try:
 a,u,g=C(),C(),C();a.call('POST','/api/auth/login',{'username':'admin','password':'TravelGuide!2026'});stamp=str(time.time_ns())[-9:]
 uid=u.call('POST','/api/auth/register',{'username':'qa_city_'+stamp,'password':'QaTravel!2026'})['user']['id']
 g.call('GET','/api/admin/city-catalog',status=401);u.call('GET','/api/admin/city-catalog',status=403);g.call('POST','/api/admin/cities',{'province_code':'410000','region_code':'410300'},status=401)
 check('新增城市和目录仅管理员可用')
 cat=a.call('GET','/api/admin/city-catalog');regions=[(p,c)for p in cat['provinces']for c in p['cities']]
 check('离线目录含34省级选项339市级地区且无区县',len(cat['provinces'])==34 and len(regions)==339 and all(c['code'].endswith('00')for p,c in regions))
 existing={c['existing_name']:c for p,c in regions if c['existing']}
 check('现有五城市及大理简称映射正确',all(n in existing for n in ['杭州','成都','南阳','泉州','大理'])and existing['大理']['code']=='532900')
 before=a.city(3);same=a.call('POST','/api/admin/cities',{'province_code':'410000','region_code':'411300'});check('已有城市复用编号且不改资料',same['id']==3 and same['created']is False and a.city(3)==before)
 a.call('POST','/api/admin/cities',{'province_code':'330000','region_code':'411300'},status=400)
 a.call('POST','/api/admin/cities',{'province_code':'110000','region_code':'110101'},status=400)
 a.call('POST','/api/admin/cities',{'province_code':'410000','region_code':'999999'},status=400)
 check('禁止跨省伪造区县及不存在的城市')
 chosen=next(((p,c)for p,c in regions if c['code']=='410300'and not c['existing']),next((p,c)for p,c in regions if not c['existing']and c['kind']=='prefecture'))
 p,c=chosen;body={'province_code':p['code'],'region_code':c['code']};out=a.call('POST','/api/admin/cities',{**body,'published':True,'name':'不得信任手填名称','slug':'nanyang'});cid=out['id'];cities.append(cid);path='/api/admin/cities/'+str(cid);row=a.city(cid)
 check('新增由目录决定归属并默认待完善',out['created']is True and row['published']==0 and row['region_code']==c['code']and row['province_code']==p['code']and row['name']==(c['name'][:-1]if c['name'].endswith('市')else c['name']))
 again=a.call('POST','/api/admin/cities',body);check('重复点击不新增第二条城市',again['id']==cid and again['created']is False and count('SELECT count(*) FROM cities WHERE region_code=?',c['code'])==1)
 check('待完善城市不进入前台目的地',cid not in [i['id']for i in g.call('GET','/api/cities')])
 currentcat=a.call('GET','/api/admin/city-catalog');choice=next(c for p in currentcat['provinces']for c in p['cities']if c['code']==body['region_code']);check('目录将新城市标记为已收录且待完善',choice['existing']is True and choice['existing_id']==cid and choice['existing_published']==0)
 p2,c2=next(((p,c)for p,c in regions if c['code']=='310000'and not c['existing']),next((p,c)for p,c in regions if not c['existing']and c['code']!=body['region_code']));b2={'province_code':p2['code'],'region_code':c2['code']}
 with ThreadPoolExecutor(max_workers=6)as pool:outs=list(pool.map(lambda _:a.call('POST','/api/admin/cities',b2),range(8)))
 second=outs[0]['id'];cities.append(second);check('并发新增同城只有一条且仅一次created',len({i['id']for i in outs})==1 and sum(i['created']for i in outs)==1)
 municipality=next(p for p in cat['provinces']if p['code']=='310000');check('直辖市自动对应自身城市',len(municipality['cities'])==1 and municipality['cities'][0]['code']=='310000')
 special=a.call('POST','/api/admin/cities',{'province_code':'810000','region_code':'810000'});
 if special['created']:cities.append(special['id'])
 check('特别行政区直接对应且保留香港简称',a.city(special['id'])['name']=='香港')
 check('缺少市级资料的省份不伪造城市选项',not next(p for p in cat['provinces']if p['code']=='710000')['cities'])
 # 任意同编码插入在数据库层也会被拒绝。
 with sqlite3.connect(DB)as db:
  blocked=False
  try:db.execute("INSERT INTO cities(region_code,slug,name,province,tagline,description,image) VALUES(?,'qa_dup','重复','河南','','','')",(c['code'],))
  except sqlite3.IntegrityError:blocked=True
  db.rollback()
 check('数据库唯一约束兜底重复编码',blocked)
 a.call('PUT',path+'/publication',{'published':True,'revision':1},status=400);check('未完善资料禁止展示且版本回滚',a.city(cid)['revision']==1)
 edits={'tagline':'QA城市短句','description':'QA城市介绍','image':'/assets/nanyang-hero.jpg','food':'','travel_advice':'','visit_advice':'','food_source':'','revision':1}
 a.call('PUT',path,{**edits,'image':'/assets/does-not-exist.jpg'},status=400);check('不存在的封面文件拒绝保存',a.city(cid)['revision']==1)
 a.call('PUT',path,edits);saved=a.city(cid);check('待完善资料可保存且保留目录身份',saved['revision']==2 and saved['region_code']==c['code']and saved['published']==0)
 a.call('PUT',path,edits,status=409);check('旧窗口不能覆盖城市资料',a.city(cid)==saved)
 a.call('PUT',path+'/publication',{'published':True,'revision':saved['revision']},status=400);check('没有已发布景点时不能展示',a.city(cid)==saved)
 spot={'city_id':cid,'title':'QA洛阳景点'+stamp,'intro':'QA简介','description':'QA洛阳正文'+stamp,'image':'/assets/nanyang-hero.jpg','published':True,'gallery':[],'theme':'人文历史','duration_minutes':120}
 sid=a.call('POST','/api/admin/spots',spot)['id'];contents.append(('spots',sid))
 guide={'city_id':cid,'title':'QA洛阳攻略'+stamp,'intro':'QA攻略简介','subtitle':'','sections':[{'title':'QA段落','body':'QA攻略正文'+stamp}],'spot_ids':[sid],'image':'/assets/nanyang-hero.jpg','published':True,'theme':'人文历史','duration_minutes':240}
 gid=a.call('POST','/api/admin/guides',guide)['id'];contents.append(('guides',gid))
 food={'city_id':cid,'title':'QA城市美食'+stamp,'category':'主食','intro':'QA简介','description':'QA描述','taste':'','tips':'','image':'','source_url':'','published':True}
 fid=a.call('POST','/api/admin/foods',food)['id'];contents.append(('foods',fid))
 check('管理员可为待完善城市准备景点攻略和美食',any(i['id']==sid for i in a.call('GET','/api/admin/spots'))and any(i['id']==gid for i in a.call('GET','/api/admin/guides')))
 for endpoint in ['/api/spots/'+str(sid),'/api/guides/'+str(gid),'/api/foods/'+str(fid),'/api/guides/'+str(gid)+'/comments','/api/guides/'+str(gid)+'/reactions']:
  g.call('GET',endpoint,status=404)
 check('待完善城市内容及直接详情均不对外开放')
 check('待完善城市内容不进入搜索或美食列表',search(g,spot['title'])['total']==0 and search(g,guide['title'],'guide')['total']==0 and g.call('GET','/api/foods?city='+str(cid))['total']==0)
 for endpoint in ['/api/favorites/spot/'+str(sid),'/api/favorites/guide/'+str(gid),'/api/food-favorites/'+str(fid)]:u.call('PUT',endpoint,status=404)
 u.call('POST','/api/me/history/spot/'+str(sid),status=404);u.call('POST','/api/guides/'+str(gid)+'/comments',{'body':'不应进入'},status=404)
 check('不能通过收藏浏览记录或评论绕过待完善隔离')
 trip={'title':'QA行程'+stamp,'days':1,'notes':'','start_date':'','checklist':[],'items':[{'spot_id':sid,'day_number':1,'note':''}]};u.call('POST','/api/me/trips',trip,status=400);check('不能伪造未展示城市景点加入私人行程')
 g.call('GET','/api/planner/options?city='+str(cid),status=404);g.call('GET','/api/recommendations?city='+str(cid),status=404);check('待完善城市不能直接用于公开规划推荐')
 a.call('PUT',path+'/publication',{'published':True,'revision':2});live=a.city(cid)
 check('满足封面介绍及景点条件后可展示',live['published']==1 and live['revision']==3 and cid in [i['id']for i in g.call('GET','/api/cities')])
 check('展示后景点攻略美食详情同步可见',g.call('GET','/api/spots/'+str(sid))['id']==sid and g.call('GET','/api/guides/'+str(gid))['id']==gid and g.call('GET','/api/foods/'+str(fid))['id']==fid)
 check('展示事件同步全文索引与美食筛选',search(g,spot['title'])['total']==1 and search(g,guide['title'],'guide')['total']==1 and g.call('GET','/api/foods?city='+str(cid))['total']==1)
 check('展示后规划选项包含新景点',sid in [i['id']for i in g.call('GET','/api/planner/options?city='+str(cid))['spots']])
 same=a.call('POST','/api/admin/cities',body);check('已展示城市再次新增仍不重复不回退',same['created']is False and a.city(cid)==live)
 a.call('PUT',path+'/publication',{'published':True,'revision':2},status=409)
 a.call('PUT',path+'/publication',{'published':False,'revision':3},status=400)
 a.call('PUT',path,{**edits,'revision':3,'description':''},status=400);check('展示后不能用旧版本或空资料破坏当前目的地',a.city(cid)==live)
finally:
 with sqlite3.connect(DB)as db:
  db.execute('PRAGMA foreign_keys=ON')
  if uid:
   db.execute('DELETE FROM trips WHERE user_id=?',(uid,))
   for t in ['spot_favorites','guide_favorites','food_favorites','comments']:db.execute('DELETE FROM '+t+' WHERE user_id=?',(uid,))
   db.execute('DELETE FROM users WHERE id=?',(uid,))
  for typ,id in reversed(contents):
   if typ=='guides':db.execute('DELETE FROM guide_spots WHERE guide_id=?',(id,))
   db.execute('DELETE FROM '+typ+' WHERE id=?',(id,))
  for id in cities:db.execute('DELETE FROM cities WHERE id=?',(id,))
  db.commit()
 g.call('GET','/api/search?q=QA')
 with sqlite3.connect(DB)as db:
  assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
  assert not db.execute('PRAGMA foreign_key_check').fetchall()
(ROOT/'target').mkdir(exist_ok=True);(ROOT/'target/city-management-checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf-8')
print('PASS',len(checks),'strict city-management HTTP/SQLite checks')
