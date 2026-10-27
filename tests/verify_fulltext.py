from pathlib import Path
import json,sqlite3,time,copy
from urllib.parse import urlencode
from concurrent.futures import ThreadPoolExecutor
from http_client import requests
BASE='http://127.0.0.1:8818';ROOT=Path(__file__).resolve().parents[1];checks=[];created=[]
def check(name,ok=True):
 if not ok:raise AssertionError(name)
 checks.append(name)
s=requests.VersionedSession();token=s.get(BASE+'/api/session').json()['csrf']
def call(method,path,body=None,status=200):
 global token
 r=s.request(method,BASE+path,json=body,headers={'X-CSRF-Token':token})
 assert r.status_code==status,(path,r.status_code,r.text[:350])
 d=r.json()
 if 'csrf'in d:token=d['csrf']
 return d
def search(q='',typ='spot',**kw):return call('GET','/api/search?'+urlencode(dict(q=q,type=typ,**kw)))
def ids(r):return [i['id']for i in r['items']]
def create(typ,title,body):
 data={'city_id':3,'title':title,'intro':'用于搜索检查的临时内容','image':'/assets/nanyang-hero.jpg','source_url':'','published':True,'theme':'人文历史','duration_minutes':120}
 if typ=='spots':data.update(description=body,highlights='',transportation='',tips='')
 else:data.update(subtitle='',sections=[{'title':'游览安排','body':body}],spot_ids=[])
 id=call('POST','/api/admin/'+typ,data)['id'];created.append((typ,id));return id,data
try:
 check('原有南阳景点和攻略按地区检索',search('南阳')['total']>=3 and search('南阳','guide')['total']>=1)
 check('组合词跨地区和正文共同匹配',23 in ids(search('南阳 汉画')))
 check('无词搜索保持原有收录顺序',ids(search(size=24))==sorted(ids(search(size=24))) and search()['sort']=='editor')
 call('POST','/api/auth/login',{'username':'admin','password':'TravelGuide!2026'})
 a,da=create('spots','青岚纸鸢','景点的临时正文。这里可观察云影。')
 b,db=create('spots','临时风景检查','景点的临时正文。青岚纸鸢。这里可观察云影。')
 c,dc=create('spots','顺序检查','这里有青岚。纸鸢，不能拼成连续短语。')
 g,dg=create('guides','临时攻略检查','铺垫文字。'*60+'苍梧石刻，是仅在攻略正文出现的测试短语。')
 check('FTS5真实索引存在并被使用',search('青岚纸鸢')['engine']=='fts5-character-phrase')
 check('标题和正文均可检索',set(ids(search('青岚纸鸢')))=={a,b})
 check('标题相关度优先于同等篇幅正文',ids(search('青岚纸鸢'))[0]==a)
 check('可切回收录顺序',ids(search('青岚纸鸢',sort='editor'))==[a,b])
 check('两个汉字支持检索',set(ids(search('纸鸢')))=={a,b,c})
 check('单个汉字支持检索',set(ids(search('鸢')))=={a,b,c})
 check('短语不能跨标点假命中',c not in ids(search('青岚纸鸢')))
 check('空格分隔关键词执行AND',set(ids(search('青岚 纸鸢')))=={a,b,c})
 check('组合词不齐全不返回',search('青岚 不存在的飞鹤')['total']==0)
 check('攻略JSON正文而非仅简介可检索',ids(search('苍梧石刻','guide'))==[g])
 item=search('苍梧石刻','guide')['items'][0];parts=item['search']['snippet']
 check('长正文自动截取命中附近片段',parts[0]['text']=='…' and any(p['match']and p['text']=='苍梧石刻'for p in parts))
 check('命中位置字段能区分正文',item['search']['fields']==['body'])
 check('返回高亮是纯文本结构',all(isinstance(p['text'],str)and isinstance(p['match'],bool)for p in parts))
 check('搜索结果不暴露内部索引或原始正文','description'not in search('青岚纸鸢')['items'][0] and 'sections'not in item)
 check('地区筛选继续生效',search('青岚纸鸢',city=1)['total']==0 and search('青岚纸鸢',city=3)['total']==2)
 check('主题筛选继续生效',search('青岚纸鸢',theme='山水风景')['total']==0)
 check('时长筛选继续生效',search('青岚纸鸢',duration='day')['total']==0 and search('青岚纸鸢',duration='half')['total']==2)
 p1=search('青岚纸鸢',size=1);p2=search('青岚纸鸢',size=1,page=2)
 check('相关度排序分页无重复且总数准确',p1['total']==p2['total']==2 and ids(p1)!=ids(p2))
 check('过大的页码不整数溢出',search('青岚纸鸢',page=2147483647)['items']==[])
 for query in ['%_\\',"' OR 1=1 --",'" OR * NEAR () :','<script>alert(1)</script>']:
  result=search(query);assert isinstance(result['total'],int)
 check('引号百分号FTS操作符和HTML不会注入或报错')
 check('只有标点不会变成全库搜索',search('%_\\')['total']==0)
 call('GET','/api/search?'+urlencode({'q':'字'*101}),status=400)
 call('GET','/api/search?sort=invalid',status=400)
 call('GET','/api/search?type=users',status=400)
 call('GET','/api/search?'+urlencode({'q':' '.join('词'+str(i)for i in range(13))}),status=400)
 check('长度关键词数排序类型边界严格拒绝')
 da['description']='暮云归舟，替换后的正文';call('PUT',f'/api/admin/spots/{a}',da)
 check('后台改正文后新词立即可检索',a in ids(search('暮云归舟')))
 check('旧标题保留且旧正文词不误留',a in ids(search('青岚纸鸢')) and a not in ids(search('云影')))
 da['title']='临时标题更新';call('PUT',f'/api/admin/spots/{a}',da)
 check('后台改标题同步清理旧索引',a not in ids(search('青岚纸鸢')) and a in ids(search('临时标题更新')))
 da['published']=False;call('PUT',f'/api/admin/spots/{a}',da)
 check('下架正文立即退出公开结果',search('暮云归舟')['total']==0)
 da['published']=True;call('PUT',f'/api/admin/spots/{a}',da)
 check('重新发布自动进入索引',ids(search('暮云归舟'))==[a])
 da.update(description='全角ＡＢＣ与中文结合。 <img src=x onerror=alert(1)>安全高亮。',tips='岸边听风是游览提示',transportation='轻舟接驳在交通中',highlights='看点是青砖旧巷')
 call('PUT',f'/api/admin/spots/{a}',da)
 check('英文大小写与全角规范化',a in ids(search('abc')) and a in ids(search('ＡＢＣ')))
 item=search('abc')['items'][0]
 check('全角原文高亮保留原始字形',any(p['match']and p['text']=='ＡＢＣ'for p in item['search']['snippet']))
 check('提示交通看点全部纳入正文',all(a in ids(search(q))for q in ['岸边听风','轻舟接驳','青砖旧巷']))
 check('正文HTML保持为文本而非接口注入',any('<img'in p['text']for p in search('安全高亮')['items'][0]['search']['snippet']))
 dg['sections']=[{'title':'更新小节名','body':'新的攻略正文与清泉石径'}];call('PUT',f'/api/admin/guides/{g}',dg)
 check('攻略段落编辑清理旧内容并索引新小节',search('苍梧石刻','guide')['total']==0 and g in ids(search('清泉石径','guide')) and g in ids(search('更新小节名','guide')))
 with sqlite3.connect(ROOT/'data/guide.db')as sql:
  old=sql.execute('SELECT name,province FROM cities WHERE id=3').fetchone()
  sql.execute("UPDATE cities SET name='南阳临时别称' WHERE id=3")
 try:check('地区名称改变同步更新所属公开内容',a in ids(search('临时别称')) and g in ids(search('临时别称','guide')))
 finally:
  with sqlite3.connect(ROOT/'data/guide.db')as sql:sql.execute('UPDATE cities SET name=?,province=? WHERE id=3',old)
 search('南阳')
 def concurrent(_):
  r=requests.get(BASE+'/api/search?'+urlencode({'q':'青岚 纸鸢'}));return r.status_code==200 and r.json()['total']==2
 with ThreadPoolExecutor(max_workers=8)as pool:check('并发搜索不会升级锁报错且结果稳定',all(pool.map(concurrent,range(24))))
 with sqlite3.connect(ROOT/'data/guide.db')as sql:
  sql.execute('DELETE FROM spots WHERE id=?',(c,))
 check('硬删除内容不会留下可检索幽灵',c not in ids(search('纸鸢')))
 with sqlite3.connect(ROOT/'data/guide.db')as sql:
  count=sql.execute("SELECT count(*) FROM search_fts WHERE type NOT IN ('spot','guide')").fetchone()[0]
 check('索引范围仅景点攻略不含私人行程评论用户',count==0)
finally:
 with sqlite3.connect(ROOT/'data/guide.db')as sql:
  sql.execute('PRAGMA foreign_keys=ON')
  for typ,id in reversed(created):sql.execute('DELETE FROM '+typ+' WHERE id=?',(id,))
 search('青岚')
 with sqlite3.connect(ROOT/'data/guide.db')as sql:
  assert sql.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
  assert sql.execute('PRAGMA foreign_key_check').fetchall()==[]
  assert sql.execute('SELECT count(*) FROM search_dirty').fetchone()[0]==0
  sql.execute("INSERT INTO search_fts(search_fts) VALUES('integrity-check')")
(ROOT/'target').mkdir(exist_ok=True)
(ROOT/'target/fulltext-checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf-8')
print('PASS',len(checks),'HTTP/SQLite fulltext checks')
